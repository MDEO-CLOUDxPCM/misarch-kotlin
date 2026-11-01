package org.misarch.simulation.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.awaitSingle
import org.misarch.common.remote.withRetries
import org.misarch.simulation.config.SimulationConfigurationProperties
import org.misarch.simulation.model.PaymentStatusUpdateDTO
import org.misarch.simulation.model.ShipmentStatusUpdateDTO
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.util.UriComponentsBuilder
import java.net.URI
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

/**
 * Sends the status callbacks to payment and shipment in the background: a callback succeeds with a 2xx answer;
 * after a failure (network error or other status) it is retried `RETRY_COUNT` times, 5 s apart, then given up with
 * a log message. The caller never waits for a callback.
 */
@Service
class CallbackService(
    private val properties: SimulationConfigurationProperties,
    private val callbackWebClient: WebClient,
    private val scope: CoroutineScope,
) {
    private val logger = LoggerFactory.getLogger(CallbackService::class.java)

    /** `POST ${PAYMENT_URL}/payment/update-payment-status` with `{"paymentId": id, "status": status}`. */
    fun sendPaymentStatus(paymentId: String, status: String): Job = send(
        UriComponentsBuilder.fromUri(properties.paymentUrl).path("/payment/update-payment-status").build().toUri(),
        PaymentStatusUpdateDTO(paymentId, status),
    )

    /** `POST ${SHIPMENT_URL}/shipment/{id}/status` with `{"status": status}`. */
    fun sendShipmentStatus(shipmentId: String, status: String): Job = send(
        UriComponentsBuilder.fromUri(properties.shipmentUrl).path("/shipment/{id}/status").build(shipmentId),
        ShipmentStatusUpdateDTO(status),
    )

    private fun send(uri: URI, body: Any): Job = scope.launch {
        val retryCount = maxOf(properties.retryCount, 0)
        try {
            withRetries(retryCount, RETRY_DELAY, { attempt, e ->
                logger.error("Error sending request to {}: {}", uri, e.toString())
                logger.info("Retrying request to {} [{}/{}]", uri, attempt, retryCount)
            }) {
                post(uri, body)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Error sending request to {}: {}", uri, e.toString())
        }
    }

    private suspend fun post(uri: URI, body: Any) {
        val response = callbackWebClient.post()
            .uri(uri)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .retrieve()
            .toBodilessEntity()
            .awaitSingle()
        check(response.statusCode.is2xxSuccessful) { "Request to $uri failed with status ${response.statusCode}" }
    }

    companion object {
        /** Pause before each retry */
        val RETRY_DELAY = 5.seconds
    }
}
