package org.misarch.simulation

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.misarch.simulation.amqp.RabbitConfigurationProperties
import org.misarch.simulation.connector.ConnectorConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration

/** The env vars are mapped by application.yaml onto the configuration properties. */
class ConfigurationPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RabbitConfigurationProperties::class, ConnectorConfigurationProperties::class)
    class Properties

    private val runner = ApplicationContextRunner()
        .withInitializer(ConfigDataApplicationContextInitializer())
        .withUserConfiguration(Properties::class.java)

    @Test
    fun `env vars are mapped, defaults apply`() {
        runner.withPropertyValues("RABBITMQ_URL=amqp://guest:guest@broker:5672").run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBean(RabbitConfigurationProperties::class.java).url).isEqualTo("amqp://guest:guest@broker:5672")
            assertThat(context.getBean(ConnectorConfigurationProperties::class.java))
                .isEqualTo(ConnectorConfigurationProperties("NOT_SET", "NOT_SET", 3))
        }
        runner.withPropertyValues(
            "RABBITMQ_URL=amqp://broker", "PAYMENT_URL=http://payment:8080", "SHIPMENT_URL=http://shipment:8080",
            "RETRY_COUNT=7",
        ).run { context ->
            assertThat(context.getBean(ConnectorConfigurationProperties::class.java))
                .isEqualTo(ConnectorConfigurationProperties("http://payment:8080", "http://shipment:8080", 7))
        }
    }

    @Test
    fun `missing RABBITMQ_URL or a non-numeric RETRY_COUNT fail the start-up`() {
        runner.run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).rootCause().hasMessage("RABBITMQ_URL is not set")
        }
        runner.withPropertyValues("RABBITMQ_URL=amqp://broker", "RETRY_COUNT=abc").run { context ->
            assertThat(context).hasFailed()
        }
    }
}
