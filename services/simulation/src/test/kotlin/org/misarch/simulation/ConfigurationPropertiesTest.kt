package org.misarch.simulation

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.misarch.simulation.config.ExperimentConfigProperties
import org.misarch.simulation.config.SimulationConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import java.net.URI

/** The env vars are mapped by application.yaml onto the configuration properties. */
class ConfigurationPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SimulationConfigurationProperties::class, ExperimentConfigProperties::class)
    class Properties

    private val runner = ApplicationContextRunner()
        .withInitializer(ConfigDataApplicationContextInitializer())
        .withUserConfiguration(Properties::class.java)

    private val required = arrayOf(
        "RABBITMQ_URL=amqp://guest:guest@broker:5672", "PAYMENT_URL=http://payment:8080", "SHIPMENT_URL=http://shipment:8080",
    )

    @Test
    fun `env vars are mapped, defaults apply`() {
        runner.withPropertyValues(*required).run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBean(SimulationConfigurationProperties::class.java)).isEqualTo(
                SimulationConfigurationProperties(
                    URI("amqp://guest:guest@broker:5672"), URI("http://payment:8080"), URI("http://shipment:8080"), 3,
                )
            )
            assertThat(context.getBean(ExperimentConfigProperties::class.java)).isEqualTo(ExperimentConfigProperties())
        }
        runner.withPropertyValues(
            *required, "RETRY_COUNT=7", "PAYMENT_PROCESSING_TIME=1", "SHIPMENT_SUCCESS_RATE=0.5", "PAYMENTS_PER_MINUTE=",
        ).run { context ->
            assertThat(context.getBean(SimulationConfigurationProperties::class.java).retryCount).isEqualTo(7)
            assertThat(context.getBean(ExperimentConfigProperties::class.java))
                .isEqualTo(ExperimentConfigProperties(paymentProcessingTime = 1, shipmentSuccessRate = 0.5))
        }
    }

    @Test
    fun `a missing URL or a non-numeric value fails the start-up`() {
        required.forEach { missing ->
            runner.withPropertyValues(*(required.toList() - missing).toTypedArray()).run { context ->
                assertThat(context).describedAs(missing).hasFailed()
            }
        }
        listOf("RETRY_COUNT=abc", "RETRY_COUNT=1.5", "SHIPMENT_PROCESSING_TIME=abc").forEach { value ->
            runner.withPropertyValues(*required, value).run { context ->
                assertThat(context).describedAs(value).hasFailed()
            }
        }
    }
}
