package org.misarch.payment.nest

import graphql.GraphQLContext
import graphql.execution.CoercedVariables
import graphql.language.FloatValue
import graphql.language.IntValue
import graphql.language.StringValue
import graphql.language.Value
import graphql.schema.Coercing
import graphql.schema.CoercingParseLiteralException
import graphql.schema.CoercingSerializeException
import graphql.schema.GraphQLScalarType
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import org.misarch.common.graphql.ScalarMapping
import org.misarch.common.nest.Js
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** A JavaScript `Date`: [instant] null = `Invalid Date`. */
data class JsDate(val instant: Instant?) {
    val isValid: Boolean get() = instant != null

    companion object {
        private val ISO = Regex(
            "^([+-]\\d{6}|\\d{4})(?:-(\\d{2})(?:-(\\d{2}))?)?" +
                "(?:[T ](\\d{2}):(\\d{2})(?::(\\d{2})(?:[.,](\\d+))?)?)?\\s*(Z|z|[+-]\\d{2}:?\\d{2})?$"
        )

        /**
         * `new Date(string)` for the ECMAScript date-time string format (and V8's tolerance for a space separator
         * and `±HHmm` offsets): date-only forms are UTC, date-time forms without offset are local time.
         * Anything else is an Invalid Date (V8's legacy formats such as `Jan 1 2024` are not supported).
         */
        fun parse(text: String, zone: ZoneId = ZoneId.systemDefault()): JsDate {
            val m = ISO.matchEntire(text.trim()) ?: return JsDate(null)
            return try {
                val year = m.groupValues[1].toInt()
                val month = m.groupValues[2].ifEmpty { "1" }.toInt()
                val day = m.groupValues[3].ifEmpty { "1" }.toInt()
                val hasTime = m.groupValues[4].isNotEmpty()
                val date = LocalDate.of(year, month, day)
                if (!hasTime) {
                    if (m.groupValues[8].isNotEmpty()) return JsDate(null)
                    return JsDate(date.atStartOfDay().toInstant(ZoneOffset.UTC))
                }
                var hour = m.groupValues[4].toInt()
                val minute = m.groupValues[5].toInt()
                val second = m.groupValues[6].ifEmpty { "0" }.toInt()
                val millis = m.groupValues[7].let { if (it.isEmpty()) 0 else (it + "00").substring(0, 3).toInt() }
                var dayShift = 0L
                if (hour == 24 && minute == 0 && second == 0 && millis == 0) { hour = 0; dayShift = 1 }
                val local = LocalDateTime.of(date, LocalTime.of(hour, minute, second, millis * 1_000_000)).plusDays(dayShift)
                val offset = m.groupValues[8]
                val instant = when {
                    offset.isEmpty() -> local.atZone(zone).toInstant()
                    offset == "Z" || offset == "z" -> local.toInstant(ZoneOffset.UTC)
                    else -> {
                        val digits = offset.substring(1).replace(":", "")
                        val total = digits.substring(0, 2).toInt() * 3600 + digits.substring(2).toInt() * 60
                        if (digits.substring(0, 2).toInt() > 23 || digits.substring(2).toInt() > 59) return JsDate(null)
                        local.toInstant(ZoneOffset.ofTotalSeconds(if (offset[0] == '-') -total else total))
                    }
                }
                JsDate(instant)
            } catch (e: Exception) {
                JsDate(null)
            }
        }
    }
}

/** A JavaScript number in a GraphQL `Float` field: integral values are written without fraction (`1999`). */
data class JsFloat(val value: Double)

/** A JSON object for the `JSONObject` scalar (graphql-type-json). */
data class JsonObjectValue(val value: Map<String, Any?>)

object PaymentScalars {

    /** @nestjs/graphql `GraphQLISODateTime`. */
    val DATE_TIME: GraphQLScalarType = GraphQLScalarType.newScalar()
        .name("DateTime")
        .description("A date-time string at UTC, such as 2019-12-03T09:54:33Z, compliant with the date-time format.")
        .coercing(object : Coercing<JsDate, String?> {
            override fun serialize(dataFetcherResult: Any, graphQLContext: GraphQLContext, locale: Locale): String? {
                val date = dataFetcherResult as? JsDate ?: return null
                val instant = date.instant ?: throw CoercingSerializeException("Invalid time value")
                return Js.toIsoString(instant)
            }

            override fun parseValue(input: Any, graphQLContext: GraphQLContext, locale: Locale): JsDate = when (input) {
                is JsDate -> input
                is Number -> JsDate(Instant.ofEpochMilli(input.toLong()))
                else -> JsDate.parse(input.toString())
            }

            override fun parseLiteral(
                input: Value<*>, variables: CoercedVariables, graphQLContext: GraphQLContext, locale: Locale
            ): JsDate? = if (input is StringValue) JsDate.parse(input.value ?: "") else null

            override fun valueToLiteral(input: Any, graphQLContext: GraphQLContext, locale: Locale): Value<*> =
                StringValue.newStringValue(input.toString()).build()
        })
        .build()

    /** graphql-type-json `JSONObject` (only used for output here). */
    val JSON_OBJECT: GraphQLScalarType = GraphQLScalarType.newScalar()
        .name("JSONObject")
        .description(
            "The `JSONObject` scalar type represents JSON objects as specified by " +
                "[ECMA-404](http://www.ecma-international.org/publications/files/ECMA-ST/ECMA-404.pdf)."
        )
        .coercing(object : Coercing<JsonObjectValue, Map<String, Any?>> {
            override fun serialize(dataFetcherResult: Any, graphQLContext: GraphQLContext, locale: Locale): Map<String, Any?> =
                (dataFetcherResult as? JsonObjectValue)?.value
                    ?: throw CoercingSerializeException("JSONObject cannot represent non-object value: $dataFetcherResult")

            override fun parseValue(input: Any, graphQLContext: GraphQLContext, locale: Locale): JsonObjectValue {
                @Suppress("UNCHECKED_CAST")
                return JsonObjectValue(input as? Map<String, Any?>
                    ?: throw CoercingSerializeException("JSONObject cannot represent non-object value: $input"))
            }

            override fun parseLiteral(
                input: Value<*>, variables: CoercedVariables, graphQLContext: GraphQLContext, locale: Locale
            ): JsonObjectValue = throw CoercingParseLiteralException("JSONObject literals are not supported")
        })
        .build()

    /** `Float` writing integral JavaScript numbers like `JSON.stringify` does. */
    val FLOAT: GraphQLScalarType = GraphQLScalarType.newScalar()
        .name("Float")
        .description("Built-in Float")
        .coercing(object : Coercing<JsFloat, Number> {
            override fun serialize(dataFetcherResult: Any, graphQLContext: GraphQLContext, locale: Locale): Number {
                val d = when (dataFetcherResult) {
                    is JsFloat -> dataFetcherResult.value
                    is Number -> dataFetcherResult.toDouble()
                    else -> throw CoercingSerializeException("Float cannot represent non numeric value: $dataFetcherResult")
                }
                if (!d.isFinite()) throw CoercingSerializeException("Float cannot represent non numeric value: $d")
                return if (d == Math.floor(d) && Math.abs(d) < 9.007199254740992E15) d.toLong() else d
            }

            override fun parseValue(input: Any, graphQLContext: GraphQLContext, locale: Locale): JsFloat =
                JsFloat((input as Number).toDouble())

            override fun parseLiteral(
                input: Value<*>, variables: CoercedVariables, graphQLContext: GraphQLContext, locale: Locale
            ): JsFloat = when (input) {
                is FloatValue -> JsFloat(input.value.toDouble())
                is IntValue -> JsFloat(BigDecimal(input.value).toDouble())
                else -> throw CoercingParseLiteralException("Float cannot represent non numeric value")
            }
        })
        .build()
}

/**
 * The payment-specific scalar beans (formerly part of the service's own `NestWebConfiguration`; the shared NestJS
 * beans now come from `org.misarch.common.nest.NestWebConfiguration`, imported by [org.misarch.payment.PaymentApplication]).
 */
@Configuration(proxyBeanMethods = false)
class PaymentScalarConfiguration {

    @Bean
    fun dateTimeScalarMapping() = ScalarMapping(JsDate::class, PaymentScalars.DATE_TIME)

    @Bean
    fun jsonObjectScalarMapping() = ScalarMapping(JsonObjectValue::class, PaymentScalars.JSON_OBJECT)

    @Bean
    fun floatScalarMapping() = ScalarMapping(JsFloat::class, PaymentScalars.FLOAT)
}
