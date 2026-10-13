package org.misarch.simulation.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.misarch.simulation.js.JsJson
import org.springframework.mock.env.MockEnvironment

class SimulationVariablesTest {

    @Test
    fun `defined variables are the original definitions in the original order`() {
        val expected = """{"PAYMENTS_PER_MINUTE":{"type":{"${'$'}schema":"http://json-schema.org/draft-07/schema#","type":"integer"},"defaultValue":1000000},""" +
            """"SHIPMENTS_PER_MINUTE":{"type":{"${'$'}schema":"http://json-schema.org/draft-07/schema#","type":"integer"},"defaultValue":1000000},""" +
            """"PAYMENT_PROCESSING_TIME":{"type":{"${'$'}schema":"http://json-schema.org/draft-07/schema#","type":"integer"},"defaultValue":5},""" +
            """"SHIPMENT_PROCESSING_TIME":{"type":{"${'$'}schema":"http://json-schema.org/draft-07/schema#","type":"integer"},"defaultValue":5},""" +
            """"PAYMENT_SUCCESS_RATE":{"type":{"${'$'}schema":"http://json-schema.org/draft-07/schema#","type":"number"},"defaultValue":0.95},""" +
            """"SHIPMENT_SUCCESS_RATE":{"type":{"${'$'}schema":"http://json-schema.org/draft-07/schema#","type":"number"},"defaultValue":0.95}}"""
        assertThat(JsJson.stringify(SimulationVariables(null).definedVariables())).isEqualTo(expected)
    }

    @Test
    fun `env values of the defined variables replace the defaults (fix S4)`() {
        val env = MockEnvironment().withProperty("PAYMENT_PROCESSING_TIME", "1").withProperty("PAYMENTS_PER_MINUTE", "7")
            .withProperty("SHIPMENT_SUCCESS_RATE", "0.5").withProperty("SHIPMENT_PROCESSING_TIME", "abc")
            .withProperty("PAYMENT_SUCCESS_RATE", " ")
        val variables = SimulationVariables(env)
        assertThat(variables.number("PAYMENT_PROCESSING_TIME")).isEqualTo(1.0)
        assertThat(variables.number("PAYMENTS_PER_MINUTE")).isEqualTo(7.0)
        assertThat(variables.number("SHIPMENT_SUCCESS_RATE")).isEqualTo(0.5)
        // non-numeric and blank values keep the default
        assertThat(variables.number("SHIPMENT_PROCESSING_TIME")).isEqualTo(5.0)
        assertThat(variables.number("PAYMENT_SUCCESS_RATE")).isEqualTo(0.95)
        assertThat(variables.number("SHIPMENTS_PER_MINUTE")).isEqualTo(1000000.0)
        assertThat(variables.env("PAYMENTS_PER_MINUTE")).isEqualTo("7")
        // the experiment-config sidecar still overrides them
        variables.setVariables(JsJson.parse("""{"PAYMENTS_PER_MINUTE":9}"""))
        assertThat(variables.number("PAYMENTS_PER_MINUTE")).isEqualTo(9.0)
        // the defined defaults (GET /ecs/defined-variables) are unchanged
        assertThat(variables.definedVariables()["PAYMENTS_PER_MINUTE"]["defaultValue"].asInt()).isEqualTo(1000000)
    }

    @Test
    fun `integer variables are coerced with parseInt(String(v))`() {
        val variables = SimulationVariables(null)
        mapOf("7" to 7.0, "\"12abc\"" to 12.0, "0.9" to 0.0, "1e21" to 1.0, "1e-7" to 1.0, "[5]" to 5.0, "\" 3\"" to 3.0)
            .forEach { (json, expected) ->
                variables.setVariables(JsJson.parse("""{"PAYMENT_PROCESSING_TIME":$json}"""))
                assertThat(variables.number("PAYMENT_PROCESSING_TIME")).describedAs(json).isEqualTo(expected)
            }
        listOf("null", "true", "{}", "\"abc\"").forEach { json ->
            variables.setVariables(JsJson.parse("""{"PAYMENTS_PER_MINUTE":$json}"""))
            assertThat(variables.number("PAYMENTS_PER_MINUTE")).describedAs(json).isNaN()
        }
    }

    @Test
    fun `number variables are coerced with Number(v)`() {
        val variables = SimulationVariables(null)
        mapOf("0.5" to 0.5, "\"0.25\"" to 0.25, "\"\"" to 0.0, "null" to 0.0, "false" to 0.0, "true" to 1.0, "[]" to 0.0)
            .forEach { (json, expected) ->
                variables.setVariables(JsJson.parse("""{"PAYMENT_SUCCESS_RATE":$json}"""))
                assertThat(variables.number("PAYMENT_SUCCESS_RATE")).describedAs(json).isEqualTo(expected)
            }
        variables.setVariables(JsJson.parse("""{"PAYMENT_SUCCESS_RATE":"abc"}"""))
        assertThat(variables.number("PAYMENT_SUCCESS_RATE")).isNaN()
    }

    @Test
    fun `unknown variable throws after the earlier entries were applied`() {
        val variables = SimulationVariables(null)
        assertThrows<UndefinedVariableException> {
            variables.setVariables(
                JsJson.parse("""{"SHIPMENT_PROCESSING_TIME":2,"pubsubDeterioration":null,"PAYMENT_PROCESSING_TIME":3}""")
            )
        }
        assertThat(variables.number("SHIPMENT_PROCESSING_TIME")).isEqualTo(2.0)
        assertThat(variables.number("PAYMENT_PROCESSING_TIME")).isEqualTo(5.0)
    }
}
