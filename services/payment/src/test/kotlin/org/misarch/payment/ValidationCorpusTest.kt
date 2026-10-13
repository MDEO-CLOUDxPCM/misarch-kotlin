package org.misarch.payment

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.misarch.payment.event.PaymentDtos
import org.misarch.payment.graphql.CardValidation
import org.misarch.payment.graphql.ConnectionArgs
import org.misarch.common.nest.ClassValidator
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Every case of the corpus generated from the ORIGINAL compiled DTO classes and the original NestJS
 * `ValidationPipe` (docs/specs/payment.md §11) gives the identical result; the clock is fixed to the generation time
 * (the expiration date validator depends on "now").
 */
class ValidationCorpusTest {

    private val mapper = JsonMapper.builder().build()

    @TestFactory
    fun corpus(): List<DynamicTest> {
        val corpus = mapper.readTree(javaClass.getResourceAsStream("/reference/payment-validation-corpus.json"))
        val clock = Clock.fixed(Instant.parse(corpus.get("generatedAt").asString()), ZoneOffset.UTC)
        val schemas = mapOf(
            "UserCreatedDto" to PaymentDtos.USER_CREATED,
            "ValidationSucceededDTO" to PaymentDtos.VALIDATION_SUCCEEDED,
            "UpdatePaymentStatusDto" to PaymentDtos.UPDATE_PAYMENT_STATUS,
            "CreateCreditCardInformationInput" to CardValidation.schema(clock),
            "FindPaymentInformationsArgs" to ConnectionArgs.FIND_ARGS,
            "FindPaymentArgs" to ConnectionArgs.FIND_ARGS,
        )
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
