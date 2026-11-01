package org.misarch.simulation.ecs

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.misarch.common.ecs.ExperimentConfigController
import org.misarch.simulation.body
import org.misarch.simulation.controllerTestClient
import org.misarch.simulation.postJson
import org.springframework.core.convert.ConversionFailedException
import org.springframework.mock.env.MockEnvironment

class ExperimentConfigVariablesTest {

    private val service = ExperimentConfigService(MockEnvironment())

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
    fun `variables are set from JSON numbers and numeric strings`() {
        client.postJson(
            "/ecs/variables",
            """{"PAYMENTS_PER_MINUTE":7,"SHIPMENTS_PER_MINUTE":"12","PAYMENT_PROCESSING_TIME":2.9,""" +
                """"SHIPMENT_PROCESSING_TIME":0,"PAYMENT_SUCCESS_RATE":1,"SHIPMENT_SUCCESS_RATE":"0.25"}""",
        ).expectStatus().isOk
        assertThat(service.paymentsPerMinute).isEqualTo(7)
        assertThat(service.shipmentsPerMinute).isEqualTo(12)
        assertThat(service.paymentProcessingTime).isEqualTo(2)
        assertThat(service.shipmentProcessingTime).isEqualTo(0)
        assertThat(service.paymentSuccessRate).isEqualTo(1.0)
        assertThat(service.shipmentSuccessRate).isEqualTo(0.25)
    }

    @Test
    fun `non-numeric values fail the request`() {
        listOf(
            """{"PAYMENT_PROCESSING_TIME":"5abc"}""",
            """{"PAYMENT_PROCESSING_TIME":true}""",
            """{"PAYMENT_PROCESSING_TIME":null}""",
            """{"PAYMENT_SUCCESS_RATE":"abc"}""",
            """{"PAYMENT_SUCCESS_RATE":{}}""",
        ).forEach { json ->
            client.postJson("/ecs/variables", json).expectStatus().is5xxServerError
        }
        assertThat(service.paymentProcessingTime).isEqualTo(5)
        assertThat(service.paymentSuccessRate).isEqualTo(0.95)
    }

    @Test
    fun `an unknown variable fails after the earlier entries were applied`() {
        client.postJson(
            "/ecs/variables", """{"SHIPMENT_PROCESSING_TIME":2,"pubsubDeterioration":null,"PAYMENT_PROCESSING_TIME":3}"""
        ).expectStatus().is5xxServerError
        assertThat(service.shipmentProcessingTime).isEqualTo(2)
        assertThat(service.paymentProcessingTime).isEqualTo(5)
    }

    @Test
    fun `env values of the variables replace the defaults (fix S4)`() {
        val env = MockEnvironment().withProperty("PAYMENT_PROCESSING_TIME", "1").withProperty("PAYMENTS_PER_MINUTE", "7")
            .withProperty("SHIPMENT_SUCCESS_RATE", "0.5").withProperty("PAYMENT_SUCCESS_RATE", "")
        val configured = ExperimentConfigService(env)
        assertThat(configured.paymentProcessingTime).isEqualTo(1)
        assertThat(configured.paymentsPerMinute).isEqualTo(7)
        assertThat(configured.shipmentSuccessRate).isEqualTo(0.5)
        // empty and unset values keep the default
        assertThat(configured.paymentSuccessRate).isEqualTo(0.95)
        assertThat(configured.shipmentsPerMinute).isEqualTo(1000000)
        assertThat(configured.shipmentProcessingTime).isEqualTo(5)
        // the experiment-config sidecar still overrides them, the defined defaults are unchanged
        ExperimentConfigVariables().paymentsPerMinuteVariable(configured).set(9)
        assertThat(configured.paymentsPerMinute).isEqualTo(9)
        assertThat(ExperimentConfigVariables().paymentsPerMinuteVariable(configured).definition.defaultValue)
            .isEqualTo(1000000)
    }

    @Test
    fun `a non-numeric env value fails`() {
        assertThrows<ConversionFailedException> {
            ExperimentConfigService(MockEnvironment().withProperty("SHIPMENT_PROCESSING_TIME", "abc"))
        }
    }
}
