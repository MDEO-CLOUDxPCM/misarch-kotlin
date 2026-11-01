package org.misarch.shipment.client

import kotlinx.coroutines.reactor.awaitSingle
import org.misarch.common.remote.withRetries
import org.misarch.shipment.ecs.ExperimentConfigService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import kotlin.time.Duration.Companion.milliseconds

/**
 * Registers shipments at the external shipment provider (`POST <MISARCH_SHIPMENT_PROVIDER_ENDPOINT>`).
 */
@Component
class ShipmentProviderClient(
    private val properties: ShipmentProviderConfigurationProperties,
    private val experimentConfigService: ExperimentConfigService,
    webClientBuilder: WebClient.Builder,
) {

    private val logger = LoggerFactory.getLogger(ShipmentProviderClient::class.java)

    private val webClient = webClientBuilder.build()

    /**
     * Registers a shipment: `providerRetries + 1` attempts with a pause of `providerRetryDelay` ms after each failed
     * one; the failure of the last attempt (network error, non-2xx answer) propagates.
     *
     * @param shipment the shipment to register
     */
    suspend fun registerShipment(shipment: ShipmentProviderShipmentDefinition) {
        withRetries(
            experimentConfigService.providerRetries,
            experimentConfigService.providerRetryDelay.milliseconds,
            { _, e -> logger.error("Failed to send shipment to external provider, retry", e) }
        ) {
            webClient.post().uri(properties.endpoint).bodyValue(shipment).retrieve().toBodilessEntity().awaitSingle()
        }
    }

}
