package org.misarch.simulation

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.misarch.common.ecs.ExperimentConfigController
import org.misarch.simulation.config.ExperimentConfigProperties
import org.misarch.simulation.ecs.ExperimentConfigService
import org.misarch.simulation.ecs.ExperimentConfigVariables
import org.misarch.simulation.model.SimulationQueue

class ExperimentConfigVariablesTest {

    private val service = ExperimentConfigService(ExperimentConfigProperties())

    private val variables = with(ExperimentConfigVariables()) {
        listOf(
            paymentsPerMinuteVariable(service),
            shipmentsPerMinuteVariable(service),
            paymentProcessingTimeVariable(service),
            shipmentProcessingTimeVariable(service),
            paymentSuccessRateVariable(service),
            shipmentSuccessRateVariable(service),
        )
    }

    private val client = controllerTestClient(ExperimentConfigController(variables))

    @Test
    fun `defined variables keep names, schemas and defaults`() {
        val schema = """"${'$'}schema":"http://json-schema.org/draft-07/schema#""""
        assertThat(client.get().uri("/ecs/defined-variables").exchange().expectStatus().isOk.body()).isEqualTo(
            """{"PAYMENTS_PER_MINUTE":{"type":{$schema,"type":"integer"},"defaultValue":1000000},""" +
                """"SHIPMENTS_PER_MINUTE":{"type":{$schema,"type":"integer"},"defaultValue":1000000},""" +
                """"PAYMENT_PROCESSING_TIME":{"type":{$schema,"type":"integer"},"defaultValue":5},""" +
                """"SHIPMENT_PROCESSING_TIME":{"type":{$schema,"type":"integer"},"defaultValue":5},""" +
                """"PAYMENT_SUCCESS_RATE":{"type":{$schema,"type":"number"},"defaultValue":0.95},""" +
                """"SHIPMENT_SUCCESS_RATE":{"type":{$schema,"type":"number"},"defaultValue":0.95}}"""
        )
    }

    @Test
    fun `variables are set from JSON numbers`() {
        client.postJson(
            "/ecs/variables",
            """{"PAYMENTS_PER_MINUTE":7,"SHIPMENTS_PER_MINUTE":12.0,"PAYMENT_PROCESSING_TIME":-1,""" +
                """"SHIPMENT_PROCESSING_TIME":0,"PAYMENT_SUCCESS_RATE":1,"SHIPMENT_SUCCESS_RATE":0.25}""",
        ).expectStatus().isOk
        assertThat(service.messagesPerMinute(SimulationQueue.PAYMENTS)).isEqualTo(7)
        assertThat(service.shipmentsPerMinute.value).isEqualTo(12)
        assertThat(service.paymentProcessingTime.value).isEqualTo(-1)
        assertThat(service.shipmentProcessingTime.value).isEqualTo(0)
        assertThat(service.paymentSuccessRate.value).isEqualTo(1.0)
        assertThat(service.shipmentSuccessRate.value).isEqualTo(0.25)
    }

    @Test
    fun `values of another type fail the request`() {
        listOf(
            """{"PAYMENT_PROCESSING_TIME":2.9}""",
            """{"PAYMENT_PROCESSING_TIME":"5"}""",
            """{"PAYMENT_PROCESSING_TIME":1e21}""",
            """{"PAYMENT_PROCESSING_TIME":true}""",
            """{"PAYMENT_PROCESSING_TIME":null}""",
            """{"PAYMENT_SUCCESS_RATE":"0.5"}""",
            """{"PAYMENT_SUCCESS_RATE":{}}""",
        ).forEach { json ->
            client.postJson("/ecs/variables", json).expectStatus().is5xxServerError
        }
        assertThat(service.paymentProcessingTime.value).isEqualTo(5)
        assertThat(service.paymentSuccessRate.value).isEqualTo(0.95)
    }

    @Test
    fun `an unknown variable fails after the earlier entries were applied`() {
        client.postJson(
            "/ecs/variables", """{"SHIPMENT_PROCESSING_TIME":2,"pubsubDeterioration":null,"PAYMENT_PROCESSING_TIME":3}"""
        ).expectStatus().is5xxServerError
        assertThat(service.shipmentProcessingTime.value).isEqualTo(2)
        assertThat(service.paymentProcessingTime.value).isEqualTo(5)
    }

    @Test
    fun `start values from the environment replace the defaults`() {
        val configured = ExperimentConfigService(
            ExperimentConfigProperties(paymentsPerMinute = 7, paymentProcessingTime = 1, shipmentSuccessRate = 0.5)
        )
        assertThat(configured.paymentProcessingTime.value).isEqualTo(1)
        assertThat(configured.paymentsPerMinute.value).isEqualTo(7)
        assertThat(configured.shipmentSuccessRate.value).isEqualTo(0.5)
        // unset values keep the default
        assertThat(configured.paymentSuccessRate.value).isEqualTo(0.95)
        assertThat(configured.shipmentsPerMinute.value).isEqualTo(1000000)
        // the experiment-config sidecar still overrides them, the defined defaults are unchanged
        configured.paymentsPerMinute.set(9)
        assertThat(configured.paymentsPerMinute.value).isEqualTo(9)
        assertThat(configured.paymentsPerMinute.definition.defaultValue).isEqualTo(1000000)
    }
}
