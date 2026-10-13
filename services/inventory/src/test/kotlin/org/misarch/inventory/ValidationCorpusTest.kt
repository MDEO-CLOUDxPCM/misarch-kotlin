package org.misarch.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.misarch.inventory.event.EventDtos
import org.misarch.inventory.graphql.ConnectionArgs
import org.misarch.common.nest.ClassValidator
import tools.jackson.databind.json.JsonMapper

/**
 * Every case of the corpus generated from the ORIGINAL compiled DTO classes and the original NestJS
 * `ValidationPipe` (see docs/specs/inventory.md §11) gives the identical result with the port's validators.
 */
class ValidationCorpusTest {

    private val mapper = JsonMapper.builder().build()

    private val schemas = mapOf(
        "OrderDTO" to EventDtos.ORDER,
        "ShipmentStatusUpdatedDto" to EventDtos.SHIPMENT,
        "PaymentEnabledDto" to EventDtos.PAYMENT_EVENT,
        "PaymentFailedDto" to EventDtos.PAYMENT_EVENT,
        "FindProductItemsArgs" to ConnectionArgs.FIND_ARGS,
        "FindProductItemsByProductVariantArgs" to ConnectionArgs.FIND_ARGS,
    )

    @TestFactory
    fun corpus(): List<DynamicTest> {
        val corpus = mapper.readTree(javaClass.getResourceAsStream("/reference/inventory-validation-corpus.json"))
        return corpus.get("cases").values().map { case ->
            val dto = case.get("dto").asString()
            DynamicTest.dynamicTest("$dto: ${case.get("name").asString()}") {
                val input = case.get("input").let { if (it.isString && it.asString() == "__undefined__") null else it }
                val expected = if (case.get("ok").asBoolean()) emptyList()
                else case.get("response").get("message").values().map { it.asString() }
                assertThat(ClassValidator.validate(schemas.getValue(dto), input)).isEqualTo(expected)
            }
        }
    }
}
