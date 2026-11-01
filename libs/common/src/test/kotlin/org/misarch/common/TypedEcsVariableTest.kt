package org.misarch.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.misarch.common.ecs.ExperimentConfigController
import org.misarch.common.ecs.IntegerVariable
import org.misarch.common.ecs.NumberVariable
import tools.jackson.databind.json.JsonMapper
import java.math.BigInteger

class TypedEcsVariableTest {

    private val jsonMapper = JsonMapper.builder().build()

    @Test
    fun `definitions list schema, type, minimum and default in this order`() {
        val controller = ExperimentConfigController(
            listOf(IntegerVariable("retries", 3, minimum = 0), NumberVariable("rate", 0.95, initialValue = 0.5))
        )
        assertEquals(
            """{"retries":{"type":{"${'$'}schema":"http://json-schema.org/draft-07/schema#","type":"integer",""" +
                """"minimum":0},"defaultValue":3},"rate":{"type":{"${'$'}schema":"http://json-schema.org/draft-07/""" +
                """schema#","type":"number"},"defaultValue":0.95}}""",
            jsonMapper.writeValueAsString(controller.getDefinedVariables()),
        )
    }

    @Test
    fun `the initial value replaces the default as start value only`() {
        val variable = IntegerVariable("limit", 1000000, initialValue = 7)
        assertEquals(7, variable.value)
        assertEquals(1000000, variable.definition.defaultValue)
        assertEquals(1000000, IntegerVariable("limit", 1000000).value)
    }

    @Test
    fun `integer variables take integral JSON numbers in range`() {
        val variable = IntegerVariable("retries", 3, minimum = 0)
        listOf<Any>(5, 0, 7L, 2.0, BigInteger.TEN).forEach { variable.set(it) }
        assertEquals(10, variable.value)
        listOf<Any>(1.5, -1, 3_000_000_000L, 1e21, "5", true, emptyMap<String, Any>(), listOf(5)).forEach { value ->
            assertThrows<IllegalArgumentException>(value.toString()) { variable.set(value) }
        }
        assertEquals(10, variable.value)
    }

    @Test
    fun `number variables take any JSON number`() {
        val variable = NumberVariable("rate", 0.95)
        variable.set(1)
        assertEquals(1.0, variable.value)
        variable.set(0.25)
        assertEquals(0.25, variable.value)
        listOf<Any>("0.5", false, listOf(1)).forEach { value ->
            assertThrows<IllegalArgumentException>(value.toString()) { variable.set(value) }
        }
        assertEquals(0.25, variable.value)
    }

    @Test
    fun `values are applied in body order until an unknown or invalid one`() {
        val a = IntegerVariable("a", 1)
        val b = IntegerVariable("b", 1)
        val controller = ExperimentConfigController(listOf(a, b))
        assertThrows<IllegalStateException> { controller.setVariables(linkedMapOf("a" to 2, "unknown" to 3, "b" to 4)) }
        assertThrows<IllegalArgumentException> { controller.setVariables(linkedMapOf("b" to 5, "a" to 0.5)) }
        assertEquals(2, a.value)
        assertEquals(5, b.value)
    }
}
