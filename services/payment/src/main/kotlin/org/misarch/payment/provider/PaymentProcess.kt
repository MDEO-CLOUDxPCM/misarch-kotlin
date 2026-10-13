package org.misarch.payment.provider

import kotlinx.coroutines.reactor.awaitSingle
import org.misarch.common.event.EventPublisher
import org.misarch.common.nest.DetachedTasks
import org.misarch.common.nest.Js
import org.misarch.common.nest.JsError
import org.misarch.common.nest.NotFoundException
import org.misarch.payment.service.OpenOrdersService
import org.misarch.payment.service.PaymentInformationService
import org.misarch.payment.service.PaymentMethods
import org.misarch.payment.service.PaymentService
import org.misarch.payment.service.PaymentStatuses
import org.misarch.payment.service.PopulatedPayment
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.ZoneId
import java.time.ZonedDateTime

object PaymentEvents {
    const val PUBSUB = "pubsub"
    const val PAYMENT_ENABLED = "payment/payment/payment-enabled"
    const val PAYMENT_FAILED = "payment/payment/payment-failed"

    /** `GET /dapr/subscribe` of the original, verbatim. */
    const val SUBSCRIPTIONS = """[{"pubsubName":"pubsub","topic":"discount/order/validation-succeeded","route":"order-validation-succeeded"},""" +
        """{"pubsubName":"pubsub","topic":"user/user/created","route":"user-created"}]"""
}

/**
 * The original `EventPublisherService` + `EventService`: payloads are `{"order": <order>}`; a failed publish is only
 * logged.
 */
@Component
class PaymentEventService(
    private val eventPublisher: EventPublisher,
    private val openOrders: OpenOrdersService,
) {
    private val logger = LoggerFactory.getLogger(PaymentEventService::class.java)
    private val mapper = JsonMapper.builder().build()

    suspend fun publish(topic: String, order: JsonNode) {
        val payload = Js.nodes.objectNode()
        payload.set("order", order)
        try {
            eventPublisher.publishEvent(PaymentEvents.PUBSUB, topic, payload)
            logger.info("Published event to topic \"{}\"", topic)
        } catch (e: Exception) {
            logger.error("Error publishing event to topic \"{}\": {}", topic, e.message)
        }
    }

    /** `buildPaymentEnabledEvent(paymentId)`: the open order must exist (NotFound otherwise). */
    suspend fun buildPaymentEnabledEvent(paymentId: String) =
        publish(PaymentEvents.PAYMENT_ENABLED, mapper.readTree(openOrders.findOne(paymentId)))

    suspend fun buildPaymentFailedEvent(paymentId: String) =
        publish(PaymentEvents.PAYMENT_FAILED, mapper.readTree(openOrders.findOne(paymentId)))
}

/**
 * The original `ConnectorService`: `POST ${SIMULATION_URL}/<endpoint>` with axios defaults; errors are logged and
 * swallowed; no request at all if `SIMULATION_URL` is unset.
 */
@Component
class ConnectorService(@Value("\${misarch.payment.simulation-url:}") private val simulationUrl: String) {
    private val logger = LoggerFactory.getLogger(ConnectorService::class.java)
    private val webClient = WebClient.builder().build()
    private val mapper = JsonMapper.builder().build()

    suspend fun send(endpoint: String, data: Map<String, Any?>) {
        if (simulationUrl.isEmpty()) {
            logger.error("Simulation URL not set")
            return
        }
        try {
            val response = webClient.post().uri("$simulationUrl/$endpoint")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Accept", "application/json, text/plain, */*")
                .bodyValue(mapper.writeValueAsString(data))
                .retrieve()
                .toBodilessEntity()
                .awaitSingle()
            if (!response.statusCode.is2xxSuccessful) logger.error("Request to {} failed with status {}", endpoint, response.statusCode)
        } catch (e: Exception) {
            logger.error("Error sending request to {}: {}", endpoint, e.message)
        }
    }
}

/**
 * The original `PaymentProviderConnectionService` with its three processors (credit card, prepayment, invoice) and
 * `EventService.startPaymentProcess` (docs/specs/payment.md §5.4, §6, §7). Work that the original did not await
 * runs as [DetachedTasks].
 */
@Component
class PaymentProcess(
    private val paymentService: PaymentService,
    private val paymentInformationService: PaymentInformationService,
    private val events: PaymentEventService,
    private val connector: ConnectorService,
    private val detached: DetachedTasks,
) {
    private val logger = LoggerFactory.getLogger(PaymentProcess::class.java)
    private val mapper = JsonMapper.builder().build()

    /**
     * `EventService.startPaymentProcess(order)` (itself not awaited by the event handler). Fix P1: a start failure
     * (unknown payment information, invalid amount, database error) stores nothing (payment and open order are
     * created in one transaction) and publishes `payment-failed`; it no longer terminates the process. Fix P4: a
     * redelivered event for an order that already has a payment is ignored.
     */
    suspend fun startPaymentProcess(order: JsonNode) {
        logger.info("Starting payment process for order with id: {}", order.get("id"))
        val created = try {
            paymentService.create(order, mapper.writeValueAsString(order)) ?: return
        } catch (e: Throwable) {
            logger.error("{startPaymentProcess} Fatal error: {}", e.message)
            events.publish(PaymentEvents.PAYMENT_FAILED, order)
            return
        }
        val (payment, info) = created
        startProvider(info.paymentMethod, payment.id, order.get("compensatableOrderAmount"))
    }

    /** `startPaymentProcess(paymentMethod, id, amount)` of the provider connection: dispatch, nothing awaited. */
    private fun startProvider(method: String, paymentId: String, amount: JsonNode?) {
        logger.info("{startPaymentProcess} Starting payment for paymentMethod: {}", method)
        val paymentType = when (method) {
            PaymentMethods.CREDIT_CARD -> "credit-card"
            PaymentMethods.PREPAYMENT -> "prepayment"
            PaymentMethods.INVOICE -> "invoice"
            else -> throw JsError("Controller for Payment Method not implemented")
        }
        if (method != PaymentMethods.PREPAYMENT) {
            // emit enabled event since everything necessary is in place
            detached.launch("payment-enabled for $paymentId") { events.buildPaymentEnabledEvent(paymentId) }
        }
        val register = registerBody(paymentId, amount, paymentType)
        detached.launch("register $paymentId") { connector.send("payment/register", register) }
        detached.launch("set $paymentId pending") { paymentService.updatePaymentStatus(paymentId, PaymentStatuses.PENDING) }
    }

    /**
     * `PaymentProviderConnectionService.updatePaymentStatus(id, status)`: returns the value the controller answers
     * with (a populated payment, or null for an empty 201).
     */
    suspend fun updatePaymentStatus(id: String, status: String): PopulatedPayment? {
        try {
            val payment = paymentService.findById(id)
            val info = payment.info ?: throw JsError("Cannot read properties of null (reading 'paymentMethod')")
            logger.info("{updatePaymentStatus} Updating payment [id] {} with method {} to status {}", id, info.paymentMethod, status)
            return when (info.paymentMethod) {
                PaymentMethods.CREDIT_CARD -> updateCreditCard(id, status)
                PaymentMethods.PREPAYMENT -> updatePrepayment(id, status)
                PaymentMethods.INVOICE -> paymentService.updatePaymentStatus(id, status)
                else -> throw NotFoundException("Controller for Payment Method not implemented")
            }
        } catch (e: Throwable) {
            logger.error("{updatePaymentStatus} Error updating payment status: {}", e.message)
            throw e
        }
    }

    /**
     * Credit card (fix P2): FAILED is retried — `numberOfRetries` is counted and the payment is registered again at
     * `${SIMULATION_URL}/payment/register` with the normal body — until [MAX_CREDIT_CARD_RETRIES] retries were made;
     * the next FAILED sets the payment FAILED and publishes `payment-failed` (once). The payment stays PENDING while
     * it is retried.
     */
    private suspend fun updateCreditCard(paymentId: String, status: String): PopulatedPayment? {
        if (status != PaymentStatuses.FAILED) return paymentService.updatePaymentStatus(paymentId, status)
        val retried = paymentService.countRetry(paymentId, MAX_CREDIT_CARD_RETRIES)
        if (retried != null) {
            logger.info("{updateCreditCard} Retrying payment {} ({}/{})", paymentId, retried.numberOfRetries?.toLong(), MAX_CREDIT_CARD_RETRIES)
            connector.send("payment/register", registerBody(paymentId, jsAmount(retried.totalAmount), "credit-card"))
            return null
        }
        val failed = paymentService.transitionPaymentStatus(paymentId, PaymentStatuses.FAILED)
        if (failed != null) {
            detached.launch("payment-failed for $paymentId") { events.buildPaymentFailedEvent(paymentId) }
            return failed
        }
        return paymentService.findById(paymentId)
    }

    /**
     * Prepayment: SUCCEEDED is stored (with `payedAt`, fix P3) and enables the order (`payment-enabled`, once);
     * FAILED is stored and publishes `payment-failed` (once, fix P5) so the reserved items are released.
     */
    private suspend fun updatePrepayment(paymentId: String, status: String): PopulatedPayment? {
        val event = when (status) {
            PaymentStatuses.SUCCEEDED -> PaymentEvents.PAYMENT_ENABLED
            PaymentStatuses.FAILED -> PaymentEvents.PAYMENT_FAILED
            else -> return paymentService.updatePaymentStatus(paymentId, status)
        }
        val updated = paymentService.transitionPaymentStatus(paymentId, status) ?: return paymentService.findById(paymentId)
        detached.launch("$event for $paymentId") {
            if (event == PaymentEvents.PAYMENT_ENABLED) events.buildPaymentEnabledEvent(paymentId)
            else events.buildPaymentFailedEvent(paymentId)
        }
        return updated
    }

    /** `RegisterPaymentDto` `{paymentId, amount, paymentType}` (key order of the original). */
    private fun registerBody(paymentId: String, amount: Any?, paymentType: String) =
        linkedMapOf("paymentId" to paymentId, "amount" to amount, "paymentType" to paymentType)

    /** A stored amount as JSON number (integral values without fraction, like `JSON.stringify`). */
    private fun jsAmount(value: Double): Any {
        return if (value == Math.floor(value) && Math.abs(value) < 9.007199254740992E15) value.toLong() else value
    }

    /** `checkOpenPayments` of the invoice processor: PENDING invoices older than 30 days fail. */
    @Scheduled(cron = "0 */15 * * * *")
    fun checkOpenInvoicePayments() {
        detached.launch("checkOpenPayments (invoice)") { checkOpenPayments(PaymentMethods.INVOICE, 30) }
    }

    /** `checkOpenPayments` of the prepayment processor: PENDING prepayments older than 7 days fail. */
    @Scheduled(cron = "0 */15 * * * *")
    fun checkOpenPrepaymentPayments() {
        detached.launch("checkOpenPayments (prepayment)") { checkOpenPayments(PaymentMethods.PREPAYMENT, 7) }
    }

    companion object {
        /** Retries of a failed credit-card payment before it fails finally (the original's `numberOfRetries >= 3`). */
        const val MAX_CREDIT_CARD_RETRIES = 3
    }

    /** One cron run (also called directly by tests with a fixed "now"). */
    suspend fun checkOpenPayments(method: String, days: Long, now: ZonedDateTime = ZonedDateTime.now(ZoneId.systemDefault())) {
        logger.info("{checkOpenPayments} Checking open payments")
        val to = org.misarch.payment.nest.JsDate(now.minusDays(days).toInstant())
        val openPayments = paymentService.find(
            mapOf("status" to PaymentStatuses.PENDING, "paymentMethod" to method, "to" to to), null, null, null
        )
        for (payment in openPayments) {
            val id = payment.row.id
            logger.info("[{}] Setting payment to failed since it is overdue", id)
            detached.launch("fail overdue $id") { paymentService.updatePaymentStatus(id, PaymentStatuses.FAILED) }
            detached.launch("payment-failed for $id") { events.buildPaymentFailedEvent(id) }
        }
    }
}
