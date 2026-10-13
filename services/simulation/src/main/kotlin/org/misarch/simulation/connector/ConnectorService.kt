package org.misarch.simulation.connector

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.misarch.simulation.config.SimulationVariables
import org.misarch.simulation.js.Js
import org.misarch.simulation.js.JsJson
import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.StringNode
import java.net.URI

/** One HTTP POST with a JSON body; returns the status code, throws on network errors. */
fun interface CallbackTransport {
    suspend fun post(uri: URI, json: String): Int
}

/**
 * Port of the original `ConnectorService`: status callbacks to payment and shipment, fire-and-forget, with
 * `RETRY_COUNT` retries (env, default 3) and a fixed 5 s pause between attempts.
 */
class ConnectorService(
    private val variables: SimulationVariables,
    private val transport: CallbackTransport,
    private val scope: CoroutineScope,
) {
    private val log = LoggerFactory.getLogger(ConnectorService::class.java)

    /** `PAYMENT_URL` / `SHIPMENT_URL`, read once at construction like in the original (`NOT_SET` if missing). */
    val paymentEndpoint: String = variables.env("PAYMENT_URL") ?: NOT_SET
    val shipmentEndpoint: String = variables.env("SHIPMENT_URL") ?: NOT_SET

    init {
        if (paymentEndpoint == NOT_SET) log.error("Payment URL not set")
        if (shipmentEndpoint == NOT_SET) log.error("Shipment URL not set")
    }

    /** `POST ${PAYMENT_URL}/payment/update-payment-status` with `{"paymentId": id, "status": status}`. */
    fun sendUpdateToPayment(paymentId: JsonNode, status: String): Job {
        val body = JsJson.objectNode().set(PAYMENT_ID, paymentId).put("status", status)
        return send("$paymentEndpoint/payment/update-payment-status", JsJson.stringify(body))
    }

    fun sendUpdateToPayment(paymentId: String, status: String): Job = sendUpdateToPayment(StringNode(paymentId), status)

    /** `POST ${SHIPMENT_URL}/shipment/${id}/status` with `{"status": status}`. */
    fun sendUpdateToShipment(shipmentId: JsonNode, status: String): Job {
        val body = JsJson.objectNode().put("status", status)
        return send("$shipmentEndpoint/shipment/${JsJson.toJsString(shipmentId)}/status", JsJson.stringify(body))
    }

    fun sendUpdateToShipment(shipmentId: String, status: String): Job = sendUpdateToShipment(StringNode(shipmentId), status)

    /**
     * `send(endpoint, data)`: attempts until success; after a failure, retries while `attempts <= RETRY_COUNT`
     * (JS comparison: a non-numeric `RETRY_COUNT` is NaN → no retry), waiting 5 s before each retry. Gives up silently.
     */
    fun send(endpoint: String, json: String): Job = scope.launch {
        val retryCount = retryCount()
        var attempts = 0
        do {
            try {
                val status = transport.post(resolve(endpoint), json)
                if (status < 200 || status > 299) throw IllegalStateException("Request to $endpoint failed with status $status")
                return@launch
            } catch (e: Exception) {
                log.error("Error sending request to {}: {}", endpoint, e.toString())
                attempts++
                // <= since first attempt is not a retry
                if (attempts <= retryCount) {
                    log.info("Retrying request to {} [{}/{}]", endpoint, attempts, Js.numberToString(retryCount))
                    delay(RETRY_DELAY_MS)
                }
            }
        } while (attempts <= retryCount)
    }

    /** `RETRY_COUNT` from the environment as JavaScript compares it (string → `Number`), default 3. */
    fun retryCount(): Double = variables.env("RETRY_COUNT")?.let { Js.stringToNumber(it) } ?: DEFAULT_RETRY_COUNT

    companion object {
        const val NOT_SET = "NOT_SET"
        const val PAYMENT_ID = "paymentId"
        const val RETRY_DELAY_MS = 5000L
        const val DEFAULT_RETRY_COUNT = 3.0

        /** axios (Node) resolves the request URL against `http://localhost` (`NOT_SET/...` → `http://localhost/NOT_SET/...`). */
        fun resolve(endpoint: String): URI = URI("http://localhost/").resolve(endpoint)
    }
}
