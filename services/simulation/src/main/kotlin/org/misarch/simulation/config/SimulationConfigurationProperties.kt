package org.misarch.simulation.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI

/**
 * Configuration of the simulation (env `PAYMENT_URL`, `SHIPMENT_URL`, both required, and `RETRY_COUNT`; the broker
 * is `spring.rabbitmq.addresses`, env `RABBITMQ_URL`)
 *
 * @property paymentUrl base URL of the payment service (status callbacks)
 * @property shipmentUrl base URL of the shipment service (status callbacks)
 * @property retryCount number of retries after a failed callback, not counting the first attempt
 */
@ConfigurationProperties("misarch.simulation")
data class SimulationConfigurationProperties(
    val paymentUrl: URI,
    val shipmentUrl: URI,
    val retryCount: Int,
)
