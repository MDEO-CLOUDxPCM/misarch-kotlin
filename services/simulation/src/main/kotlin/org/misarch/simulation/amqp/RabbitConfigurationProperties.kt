package org.misarch.simulation.amqp

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration of the RabbitMQ broker (env `RABBITMQ_URL`, required)
 *
 * @property url AMQP URL used by the consumer and both publishers
 */
@ConfigurationProperties("misarch.simulation.rabbitmq")
data class RabbitConfigurationProperties(val url: String) {
    init {
        check(url.isNotEmpty()) { "RABBITMQ_URL is not set" }
    }
}
