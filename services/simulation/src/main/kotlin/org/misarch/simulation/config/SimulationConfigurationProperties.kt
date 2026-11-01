package org.misarch.simulation.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI

/**
 * Configuration of the simulation (env `RABBITMQ_URL`, `PAYMENT_URL`, `SHIPMENT_URL`, all required, and `RETRY_COUNT`)
 *
 * @property rabbitmqUrl AMQP URL of the broker, used by the consumer and both publishers
 * @property paymentUrl base URL of the payment service (status callbacks)
 * @property shipmentUrl base URL of the shipment service (status callbacks)
 * @property retryCount number of retries after a failed callback, not counting the first attempt
 */
@ConfigurationProperties("misarch.simulation")
data class SimulationConfigurationProperties(
    val rabbitmqUrl: URI,
    val paymentUrl: URI,
    val shipmentUrl: URI,
    val retryCount: Int,
)
