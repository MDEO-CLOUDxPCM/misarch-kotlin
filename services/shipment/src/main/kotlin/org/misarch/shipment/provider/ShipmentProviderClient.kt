package org.misarch.shipment.provider

import kotlinx.coroutines.delay
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.misarch.shipment.ecs.ExperimentConfigService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient

/**
 * Registers shipments at the external shipment provider (`POST <MISARCH_SHIPMENT_PROVIDER_ENDPOINT>`)
 *
 * @property properties the provider configuration
 * @property experimentConfigService the retries of the provider calls
 */
@Component
class ShipmentProviderClient(
    private val properties: ShipmentProviderConfigurationProperties,
    private val experimentConfigService: ExperimentConfigService
) {

    private val logger = LoggerFactory.getLogger(ShipmentProviderClient::class.java)

    /**
     * Registers a shipment with retries: `providerRetries` attempts with a delay of `providerRetryDelay` ms after
     * each failure, then a last attempt whose failure (network error, non-2xx answer) propagates
     * (i.e. `providerRetries + 1` attempts in total).
     *
     * @param shipment the shipment to register
     */
    suspend fun registerShipment(shipment: ShipmentProviderShipmentDefinition) {
        // a new WebClient (default codecs: Jackson, nulls included) per shipment, like the original
        val webClient = WebClient.create()
        repeat(experimentConfigService.providerRetries) {
            try {
                post(webClient, shipment)
                return
            } catch (e: Exception) {
                logger.error("Failed to send shipment to external provider, retry", e)
                delay(experimentConfigService.providerRetryDelay.toLong())
            }
        }
        post(webClient, shipment)
    }

    private suspend fun post(webClient: WebClient, shipment: ShipmentProviderShipmentDefinition) {
        webClient.post().uri(properties.endpoint).bodyValue(shipment).retrieve().toBodilessEntity().awaitSingleOrNull()
    }

}
