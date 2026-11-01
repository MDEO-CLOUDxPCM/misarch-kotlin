package org.misarch.shipment.provider

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI

/**
 * Configuration of the external shipment provider (env `MISARCH_SHIPMENT_PROVIDER_ENDPOINT`, required).
 *
 * @property endpoint the URL shipments are registered at (`POST`)
 */
@ConfigurationProperties("misarch.shipment.provider")
data class ShipmentProviderConfigurationProperties(val endpoint: URI)
