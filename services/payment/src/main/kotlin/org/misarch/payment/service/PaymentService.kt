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
import org.misarch.payment.provider.PaymentProviderClient
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Service for [PaymentEntity]s: payment creation for validated orders, the provider's status updates and the jobs
 * that fail overdue payments. Events and provider calls are part of the operation: a failure rolls it back and
 * propagates (the event or callback is redelivered).
 *
 * @property repository the payment repository
 * @property paymentInformationRepository the payment information repository
 * @property openOrderRepository stores the orders of the payments
 * @property paymentProviderClient registers payments at the provider
 * @property eventPublisher publishes `payment-enabled` / `payment-failed`
 * @property conditionQueries inserts payments with a pre-generated id
 * @property jsonMapper reads and writes the orders
 */
@Service
class PaymentService(
    private val repository: PaymentRepository,
    private val paymentInformationRepository: PaymentInformationRepository,
    private val openOrderRepository: OpenOrderRepository,
    private val paymentProviderClient: PaymentProviderClient,
    private val eventPublisher: EventPublisher,
    private val conditionQueries: ConditionQueries,
    private val jsonMapper: JsonMapper,
) {

    private val logger = LoggerFactory.getLogger(PaymentService::class.java)

    /**
     * Creates the payment of a validated order and registers it at the provider; payments that are not prepaid
     * enable the order right away (`payment-enabled`). An order without a valid payment information or amount
     * publishes `payment-failed` and stores nothing. Idempotent: an order that already has a payment is ignored.
     *
     * @param order the order, stored and relayed unchanged
     */
    @Transactional
    suspend fun createPaymentForOrder(order: JsonNode) {
        val orderDTO = try {
            jsonMapper.treeToValue(order, OrderDTO::class.java)
        } catch (e: JacksonException) {
            logger.error("Cannot create a payment for the invalid order {}: {}", order.get("id"), e.originalMessage)
            eventPublisher.publishEvent(PaymentEvents.PAYMENT_FAILED, PaymentEventDTO(order))
            return
        }
        val paymentInformation = paymentInformationRepository.findById(orderDTO.paymentInformationId)
        if (paymentInformation == null) {
            logger.error("Payment information {} of order {} not found", orderDTO.paymentInformationId, orderDTO.id)
            eventPublisher.publishEvent(PaymentEvents.PAYMENT_FAILED, PaymentEventDTO(order))
            return
        }
        val paymentId = UUID.randomUUID()
        val orderJson = Json.of(jsonMapper.writeValueAsString(order))
        if (openOrderRepository.createOpenOrder(paymentId, orderDTO.id, orderJson) == 0) {
            logger.info("Order {} already has a payment, ignoring the redelivered event", orderDTO.id)
            return
        }
        val now = OffsetDateTime.now()
        conditionQueries.insert(
            PaymentEntity(
                totalAmount = orderDTO.compensatableOrderAmount,
                status = PaymentStatus.PENDING,
                paymentInformationId = paymentInformation.id!!,
                payedAt = null,
                numberOfRetries = 0,
                createdAt = now,
                updatedAt = now,
                id = paymentId
            )
        )
        paymentProviderClient.registerPayment(paymentId, orderDTO.compensatableOrderAmount, paymentInformation.paymentMethod)
        if (paymentInformation.paymentMethod != PaymentMethod.PREPAYMENT) {
            eventPublisher.publishEvent(PaymentEvents.PAYMENT_ENABLED, PaymentEventDTO(order))
        }
    }

    /**
     * Applies a status reported by the payment provider:
     * - credit card: `FAILED` is retried (counted in `numberOfRetries`, registered again) until [MAX_CREDIT_CARD_RETRIES]
     *   retries were made; the next `FAILED` fails the payment and publishes `payment-failed`;
     * - prepayment: `SUCCEEDED` enables the order (`payment-enabled`), `FAILED` publishes `payment-failed`;
     * - every other status is stored as is (`SUCCEEDED` with `payedAt`).
     * Events are published once, on the transition to the status.
     *
     * @param id the payment
     * @param status the reported status
     */
    @Transactional
    suspend fun updatePaymentStatus(id: UUID, status: PaymentStatus) {
        val payment = repository.findByIdForUpdate(id) ?: throw NoSuchElementException("Payment with id \"$id\" not found")
        val paymentInformation = paymentInformationRepository.findByIdOrThrow(payment.paymentInformationId) {
            "Payment information ${payment.paymentInformationId} of payment $id not found"
        }
        logger.info("Updating payment {} ({}) to {}", id, paymentInformation.paymentMethod, status)
        when {
            paymentInformation.paymentMethod == PaymentMethod.CREDIT_CARD && status == PaymentStatus.FAILED &&
                payment.numberOfRetries < MAX_CREDIT_CARD_RETRIES -> retryCreditCardPayment(payment)
            paymentInformation.paymentMethod == PaymentMethod.CREDIT_CARD && status == PaymentStatus.FAILED ->
                transitionPayment(payment, status, PaymentEvents.PAYMENT_FAILED)
            paymentInformation.paymentMethod == PaymentMethod.PREPAYMENT && status == PaymentStatus.SUCCEEDED ->
                transitionPayment(payment, status, PaymentEvents.PAYMENT_ENABLED)
            paymentInformation.paymentMethod == PaymentMethod.PREPAYMENT && status == PaymentStatus.FAILED ->
                transitionPayment(payment, status, PaymentEvents.PAYMENT_FAILED)
            else -> setStatus(payment, status)
        }
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

    private suspend fun retryCreditCardPayment(payment: PaymentEntity) {
        payment.numberOfRetries++
        payment.updatedAt = OffsetDateTime.now()
        repository.save(payment)
        logger.info("Retrying payment {} ({}/{})", payment.id, payment.numberOfRetries, MAX_CREDIT_CARD_RETRIES)
        paymentProviderClient.registerPayment(payment.id!!, payment.totalAmount, PaymentMethod.CREDIT_CARD)
    }

    private suspend fun transitionPayment(payment: PaymentEntity, status: PaymentStatus, topic: String) {
        if (payment.status == status) {
            return
        }
        setStatus(payment, status)
        val openOrder = openOrderRepository.findByIdOrThrow(payment.id!!) { "Open order for payment ${payment.id} not found" }
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
        eventPublisher.publishEvent(topic, PaymentEventDTO(jsonMapper.readTree(orderJson.asString())))
    }

    /**
     * Deletes a payment
     *
     * @param id the payment
     * @return the deleted payment
     */
    @Transactional
    suspend fun deletePayment(id: UUID): PaymentEntity {
        val payment = repository.findByIdOrThrow(id) { "Payment with id \"$id\" not found" }
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
