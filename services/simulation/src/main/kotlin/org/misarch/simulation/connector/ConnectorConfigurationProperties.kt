package org.misarch.simulation.connector

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration of the status callbacks (env `PAYMENT_URL`, `SHIPMENT_URL`, `RETRY_COUNT`)
 *
 * @property paymentUrl base URL of the payment service
 * @property shipmentUrl base URL of the shipment service
 * @property retryCount number of retries after a failed callback (not including the first attempt)
 */
@ConfigurationProperties("misarch.simulation.connector")
data class ConnectorConfigurationProperties(
    val paymentUrl: String = NOT_SET,
    val shipmentUrl: String = NOT_SET,
    val retryCount: Int = 3,
) {
    companion object {
        /** Value of a missing URL */
        const val NOT_SET = "NOT_SET"
    }
}
