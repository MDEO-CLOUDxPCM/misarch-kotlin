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
    private val openOrders: OpenOrdersService,
    private val events: PaymentEventService,
    private val connector: ConnectorService,
    private val detached: DetachedTasks,
) {
    private val logger = LoggerFactory.getLogger(PaymentProcess::class.java)
    private val mapper = JsonMapper.builder().build()

    /** `EventService.startPaymentProcess(order)` (itself not awaited by the event handler). */
    suspend fun startPaymentProcess(order: JsonNode) {
        logger.info("Starting payment process for order with id: {}", order.get("id"))
        try {
            val (payment, info) = paymentService.create(order)
            openOrders.create(payment.id, mapper.writeValueAsString(order))
            startProvider(info.paymentMethod, payment.id, order.get("compensatableOrderAmount"))
        } catch (e: Throwable) {
            logger.error("{startPaymentProcess} Fatal error: {}", e.message)
            // the original starts `openOrdersService.delete(order.id)` (never matches: keyed by payment id) without
            // awaiting it and publishes payment-failed; the rejected delete then terminates the process
            events.publish(PaymentEvents.PAYMENT_FAILED, order)
            val orderId = order.get("id")?.takeUnless { it.isNull }?.let { Js.toJsString(it) }
            openOrders.delete(orderId)
        }
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
        val register = linkedMapOf<String, Any?>("paymentId" to paymentId, "amount" to amount, "paymentType" to paymentType)
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

    private suspend fun updateCreditCard(paymentId: String, status: String): PopulatedPayment? {
        if (status != PaymentStatuses.FAILED) return paymentService.updatePaymentStatus(paymentId, status)
        val payment = paymentService.findById(paymentId)
        // numberOfRetries is never stored, `undefined >= 3` is false: this branch is unreachable in practice
        if ((payment.row.numberOfRetries ?: Double.NaN) >= 3) {
            detached.launch("payment-failed for $paymentId") { events.buildPaymentFailedEvent(paymentId) }
            return paymentService.updatePaymentStatus(paymentId, status)
        }
        // retry: wrong path ("register" instead of "payment/register"), the simulation answers 404
        connector.send("register", linkedMapOf("paymentId" to paymentId, "type" to "credit-card"))
        return null
    }

    private suspend fun updatePrepayment(paymentId: String, status: String): PopulatedPayment? {
        if (status != PaymentStatuses.SUCCEEDED) return paymentService.updatePaymentStatus(paymentId, status)
        // the status is NOT stored (quirk); only the enabled event is emitted
        detached.launch("payment-enabled for $paymentId") { events.buildPaymentEnabledEvent(paymentId) }
        return null
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
