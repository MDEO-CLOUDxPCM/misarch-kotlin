package org.misarch.payment.client

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI

/**
 * Configuration of the external payment provider (env `SIMULATION_URL`, required).
 *
 * @property simulationUrl base URL of the provider (`POST <simulationUrl>/payment/register`)
 */
@ConfigurationProperties("misarch.payment.provider")
data class PaymentProviderConfigurationProperties(val simulationUrl: URI)
