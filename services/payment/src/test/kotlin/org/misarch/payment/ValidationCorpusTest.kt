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
 * (the expiration date validator depends on "now"). Exception: the expiration-date cases of
 * [FIXED_INVALID_EXPIRATION] (defect fix P11).
 */
class ValidationCorpusTest {

    companion object {
        /**
         * Expiration dates the original accepted although they are not `MM/YY` / `MM/YYYY` (its `parseInt`-based
         * validator); rejected since fix P11 (docs/fixes/payment.md). All other cases keep the original's result.
         */
        val FIXED_INVALID_EXPIRATION = setOf(
            "expiry \"+5/30\"", "expiry \"05/+30\"", "expiry \"05/30/99\"", "expiry \"05-30\"", "expiry \"abc\"",
            "expiry \"12\"", "expiry \"12/\"", "expiry \"/30\"", "expiry \"\"", "expiry \"12/30abc\"",
            "expiry \"12abc/30\"", "expiry \"1e1/30\"",
        )
    }

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
                val name = case.get("name").asString()
                val expected = when {
                    dto == "CreateCreditCardInformationInput" && name in FIXED_INVALID_EXPIRATION ->
                        listOf("The expiration date is invalid or expired")
                    case.get("ok").asBoolean() -> emptyList()
                    else -> case.get("response").get("message").values().map { it.asString() }
                }
                assertThat(ClassValidator.validate(schemas.getValue(dto), input)).isEqualTo(expected)
            }
        }
    }
}
