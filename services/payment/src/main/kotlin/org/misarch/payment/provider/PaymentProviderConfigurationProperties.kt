package org.misarch.payment.provider

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration of the external payment provider (env `SIMULATION_URL`)
 *
 * @property simulationUrl base URL of the provider (`POST <simulationUrl>/payment/register`); empty: payments are
 *   not registered at a provider
 */
@ConfigurationProperties("misarch.payment.provider")
data class PaymentProviderConfigurationProperties(val simulationUrl: String = "")
