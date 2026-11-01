package org.misarch.payment.service

import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.flow.toList
import org.misarch.common.event.EventPublisher
import org.misarch.common.persistence.ConditionQueries
import org.misarch.common.persistence.findByIdOrThrow
import org.misarch.payment.event.PaymentEvents
import org.misarch.payment.event.model.OrderDTO
import org.misarch.payment.event.model.PaymentEventDTO
import org.misarch.payment.graphql.model.PaymentMethod
import org.misarch.payment.graphql.model.PaymentStatus
import org.misarch.payment.persistence.model.PaymentEntity
import org.misarch.payment.persistence.repository.OpenOrderRepository
import org.misarch.payment.persistence.repository.PaymentInformationRepository
import org.misarch.payment.persistence.repository.PaymentRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Service for [PaymentEntity]s: stores the payments of validated orders (registered at the provider afterwards by
 * [PaymentRegistrationService]), removes payments whose registration failed, applies the provider's status updates
 * and runs the jobs that fail overdue payments. Events are published in the transaction of the write: a failure
 * rolls it back and propagates (the event or callback is redelivered).
 *
 * @property repository the payment repository
 * @property paymentInformationRepository the payment information repository
 * @property openOrderRepository stores the orders of the payments
 * @property eventPublisher publishes `payment-enabled` / `payment-failed`
 * @property conditionQueries inserts payments with a pre-generated id
 * @property jsonMapper writes the stored orders and reads them back
 */
@Service
class PaymentService(
    private val repository: PaymentRepository,
    private val paymentInformationRepository: PaymentInformationRepository,
    private val openOrderRepository: OpenOrderRepository,
    private val eventPublisher: EventPublisher,
    private val conditionQueries: ConditionQueries,
    private val jsonMapper: JsonMapper,
) {

    private val logger = LoggerFactory.getLogger(PaymentService::class.java)

    /**
     * Stores the PENDING payment of a validated order and its order. An order with an unknown payment information
     * publishes `payment-failed` and stores nothing. Idempotent: an order that already has a payment is ignored.
     *
     * @param order the order, stored and relayed in the payment events
     * @return the stored payment, to be registered at the provider, or null
     */
    @Transactional
    suspend fun createPayment(order: OrderDTO): PendingPayment? {
        val paymentInformation = paymentInformationRepository.findById(order.paymentInformationId)
        if (paymentInformation == null) {
            logger.error("Payment information {} of order {} not found", order.paymentInformationId, order.id)
            eventPublisher.publishEvent(PaymentEvents.PAYMENT_FAILED, PaymentEventDTO(order))
            return null
        }
        val paymentId = UUID.randomUUID()
        val orderJson = Json.of(jsonMapper.writeValueAsString(order))
        if (openOrderRepository.createOpenOrder(paymentId, order.id, orderJson) == 0) {
            logger.info("Order {} already has a payment, ignoring the redelivered event", order.id)
            return null
        }
        val now = OffsetDateTime.now()
        val payment = conditionQueries.insert(
            PaymentEntity(
                totalAmount = order.compensatableOrderAmount,
                status = PaymentStatus.PENDING,
                paymentInformationId = paymentInformation.id!!,
                payedAt = null,
                numberOfRetries = 0,
                createdAt = now,
                updatedAt = now,
                id = paymentId
            )
        )
        return PendingPayment(payment, paymentInformation.paymentMethod, order)
    }

    /**
     * Removes a payment whose registration at the provider failed, with its order (the redelivered event creates it
     * again)
     *
     * @param id the payment
     */
    @Transactional
    suspend fun discardPayment(id: UUID) {
        logger.error("Registration of payment {} at the provider failed, removing it", id)
        openOrderRepository.deleteById(id)
        repository.deleteById(id)
    }

    /**
     * Applies a status reported by the payment provider:
     * - credit card: `FAILED` is retried (counted in `numberOfRetries`, the caller registers the payment again) until
     *   [MAX_CREDIT_CARD_RETRIES] retries were made; the next `FAILED` fails the payment and publishes `payment-failed`;
     * - prepayment: `SUCCEEDED` enables the order (`payment-enabled`), `FAILED` publishes `payment-failed`;
     * - every other status is stored as is (`SUCCEEDED` with `payedAt`).
     * Events are published once, on the transition to the status.
     *
     * @param id the payment
     * @param status the reported status
     * @return the payment if it is retried (to be registered at the provider again), else null
     */
    @Transactional
    suspend fun updatePaymentStatus(id: UUID, status: PaymentStatus): PaymentEntity? {
        val payment = repository.findByIdForUpdate(id) ?: throw NoSuchElementException()
        val paymentInformation = paymentInformationRepository.findByIdOrThrow(payment.paymentInformationId)
        logger.info("Updating payment {} ({}) to {}", id, paymentInformation.paymentMethod, status)
        when {
            paymentInformation.paymentMethod == PaymentMethod.CREDIT_CARD && status == PaymentStatus.FAILED &&
                payment.numberOfRetries < MAX_CREDIT_CARD_RETRIES -> return retryCreditCardPayment(payment)
            paymentInformation.paymentMethod == PaymentMethod.CREDIT_CARD && status == PaymentStatus.FAILED ->
                transitionPayment(payment, status, PaymentEvents.PAYMENT_FAILED)
            paymentInformation.paymentMethod == PaymentMethod.PREPAYMENT && status == PaymentStatus.SUCCEEDED ->
                transitionPayment(payment, status, PaymentEvents.PAYMENT_ENABLED)
            paymentInformation.paymentMethod == PaymentMethod.PREPAYMENT && status == PaymentStatus.FAILED ->
                transitionPayment(payment, status, PaymentEvents.PAYMENT_FAILED)
            else -> setStatus(payment, status)
        }
        return null
    }

    /**
     * Fails the PENDING invoice payments older than 30 days (`payment-failed`)
     */
    @Scheduled(cron = "0 */15 * * * *")
    @Transactional
    suspend fun failOverdueInvoicePayments() {
        failOverduePayments(PaymentMethod.INVOICE, INVOICE_DAYS)
    }

    /**
     * Fails the PENDING prepayments older than 7 days (`payment-failed`)
     */
    @Scheduled(cron = "0 */15 * * * *")
    @Transactional
    suspend fun failOverduePrepaymentPayments() {
        failOverduePayments(PaymentMethod.PREPAYMENT, PREPAYMENT_DAYS)
    }

    private suspend fun failOverduePayments(paymentMethod: PaymentMethod, days: Long) {
        val overdue = repository.findAllForUpdateByStatusAndPaymentMethodCreatedBefore(
            PaymentStatus.PENDING, paymentMethod, OffsetDateTime.now().minusDays(days)
        )
        logger.info("{} overdue {} payments", overdue.size, paymentMethod)
        val orders = openOrderRepository.findAllById(overdue.map { it.id!! }).toList().associateBy { it.paymentId }
        for (payment in overdue) {
            setStatus(payment, PaymentStatus.FAILED)
            orders[payment.id]?.let { publishOrderEvent(PaymentEvents.PAYMENT_FAILED, it.orderJson) }
        }
    }

    private suspend fun retryCreditCardPayment(payment: PaymentEntity): PaymentEntity {
        payment.numberOfRetries++
        payment.updatedAt = OffsetDateTime.now()
        logger.info("Retrying payment {} ({}/{})", payment.id, payment.numberOfRetries, MAX_CREDIT_CARD_RETRIES)
        return repository.save(payment)
    }

    private suspend fun transitionPayment(payment: PaymentEntity, status: PaymentStatus, topic: String) {
        if (payment.status == status) {
            return
        }
        setStatus(payment, status)
        val openOrder = openOrderRepository.findByIdOrThrow(payment.id!!)
        publishOrderEvent(topic, openOrder.orderJson)
    }

    private suspend fun setStatus(payment: PaymentEntity, status: PaymentStatus) {
        val now = OffsetDateTime.now()
        payment.status = status
        payment.updatedAt = now
        if (status == PaymentStatus.SUCCEEDED) {
            payment.payedAt = now
        }
        repository.save(payment)
    }

    private suspend fun publishOrderEvent(topic: String, orderJson: Json) {
        eventPublisher.publishEvent(topic, PaymentEventDTO(jsonMapper.readValue(orderJson.asString(), OrderDTO::class.java)))
    }

    /**
     * Deletes a payment
     *
     * @param id the payment
     * @return the deleted payment
     */
    @Transactional
    suspend fun deletePayment(id: UUID): PaymentEntity {
        val payment = repository.findByIdOrThrow(id)
        repository.delete(payment)
        return payment
    }

    companion object {
        /** Retries of a failed credit card payment before it fails */
        const val MAX_CREDIT_CARD_RETRIES = 3

        /** Days after which a pending invoice payment fails */
        const val INVOICE_DAYS = 30L

        /** Days after which a pending prepayment fails */
        const val PREPAYMENT_DAYS = 7L
    }

}

/**
 * A stored PENDING payment that is registered at the provider next
 *
 * @property payment the stored payment
 * @property paymentMethod the payment method of its payment information
 * @property order the order, relayed in `payment-enabled`
 */
class PendingPayment(
    val payment: PaymentEntity,
    val paymentMethod: PaymentMethod,
    val order: OrderDTO
)
