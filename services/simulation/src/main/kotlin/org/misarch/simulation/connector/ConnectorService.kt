package org.misarch.simulation.connector

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.misarch.simulation.connector.ConnectorConfigurationProperties.Companion.NOT_SET
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper
import java.net.URI

/**
 * Sends the status callbacks to payment and shipment, fire-and-forget, with `RETRY_COUNT` retries and a fixed
 * pause of 5 s between attempts.
 */
@Service
class ConnectorService(
    private val properties: ConnectorConfigurationProperties,
    private val transport: CallbackTransport,
    private val jsonMapper: JsonMapper,
    private val scope: CoroutineScope,
) {
    private val log = LoggerFactory.getLogger(ConnectorService::class.java)

    init {
        if (properties.paymentUrl == NOT_SET) log.error("Payment URL not set")
        if (properties.shipmentUrl == NOT_SET) log.error("Shipment URL not set")
    }

    /** `POST ${PAYMENT_URL}/payment/update-payment-status` with `{"paymentId": id, "status": status}`. */
    fun sendUpdateToPayment(paymentId: String, status: String): Job =
        send("${properties.paymentUrl}/payment/update-payment-status", PaymentStatusUpdateDTO(paymentId, status))

    /** `POST ${SHIPMENT_URL}/shipment/${id}/status` with `{"status": status}`. */
    fun sendUpdateToShipment(shipmentId: String, status: String): Job =
        send("${properties.shipmentUrl}/shipment/$shipmentId/status", ShipmentStatusUpdateDTO(status))

    /**
     * Attempts until success (2xx); after a failure, retries while `attempts <= RETRY_COUNT`, waiting 5 s before
     * each retry. Gives up with a log message only.
     */
    private fun send(endpoint: String, body: Any): Job = scope.launch {
        val json = jsonMapper.writeValueAsString(body)
        val retryCount = properties.retryCount
        var attempts = 0
        do {
            try {
                val status = transport.post(resolve(endpoint), json)
                check(status in 200..299) { "Request to $endpoint failed with status $status" }
                return@launch
            } catch (e: Exception) {
                log.error("Error sending request to {}: {}", endpoint, e.toString())
                attempts++
                // <= since first attempt is not a retry
                if (attempts <= retryCount) {
                    log.info("Retrying request to {} [{}/{}]", endpoint, attempts, retryCount)
                    delay(RETRY_DELAY_MS)
                }
            }
        } while (attempts <= retryCount)
    }

    companion object {
        /** Pause between two attempts */
        const val RETRY_DELAY_MS = 5000L

        /** Resolves an endpoint against `http://localhost/` (`NOT_SET/...` -> `http://localhost/NOT_SET/...`). */
        fun resolve(endpoint: String): URI = URI("http://localhost/").resolve(endpoint)
    }
}
