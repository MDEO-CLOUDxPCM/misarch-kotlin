package org.misarch.common.rust

import graphql.GraphQLContext
import graphql.execution.CoercedVariables
import graphql.language.StringValue
import graphql.language.Value
import graphql.schema.Coercing
import graphql.schema.CoercingParseLiteralException
import graphql.schema.CoercingParseValueException
import graphql.schema.CoercingSerializeException
import graphql.schema.GraphQLScalarType
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Timestamps of the Rust originals (`bson::DateTime`, millisecond precision, rendered by chrono).
 * Kotlin type: [Instant] (UTC).
 */
object RustDateTime {

    private val BASE: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC)

    /** `bson::DateTime::now()`: current time truncated to milliseconds. */
    fun now(): Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS)

    /** chrono `SecondsFormat::AutoSi` date-time part (fraction of 0, 3, 6 or 9 digits). */
    private fun autoSi(instant: Instant): String {
        val nanos = instant.nano
        val fraction = when {
            nanos == 0 -> ""
            nanos % 1_000_000 == 0 -> ".%03d".format(nanos / 1_000_000)
            nanos % 1_000 == 0 -> ".%06d".format(nanos / 1_000)
            else -> ".%09d".format(nanos)
        }
        return BASE.format(instant) + fraction
    }

    /** `DateTime<Utc>::to_rfc3339()` - the GraphQL `DateTime` output of async-graphql: `...+00:00`. */
    fun toRfc3339(instant: Instant): String = autoSi(instant) + "+00:00"

    /** chrono's serde format (event JSON of the Rust services): `...Z`. */
    fun toSerde(instant: Instant): String = autoSi(instant) + "Z"

    /**
     * chrono `DateTime<Utc>` deserialization (relaxed RFC 3339: `T`, `t` or space as separator, `Z`/`z` or a
     * `±hh:mm` offset), converted to UTC; null if not parsable.
     */
    fun parseOrNull(s: String): Instant? {
        val normalized = s.trim().let {
            if (it.length > 10 && (it[10] == ' ' || it[10] == 't')) it.substring(0, 10) + "T" + it.substring(11) else it
        }.let { if (it.endsWith("z")) it.dropLast(1) + "Z" else it }
        return try {
            OffsetDateTime.parse(normalized, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
        } catch (e: DateTimeParseException) {
            null
        }
    }

    /** The `DateTime` scalar (async-graphql chrono/bson) for [Instant] values. */
    val SCALAR: GraphQLScalarType = GraphQLScalarType.newScalar()
        .name("DateTime")
        .coercing(object : Coercing<Instant, String> {
            override fun serialize(dataFetcherResult: Any, graphQLContext: GraphQLContext, locale: Locale): String =
                when (dataFetcherResult) {
                    is Instant -> toRfc3339(dataFetcherResult)
                    is OffsetDateTime -> toRfc3339(dataFetcherResult.toInstant())
                    else -> throw CoercingSerializeException("Expected an Instant, got ${dataFetcherResult::class.simpleName}")
                }

            override fun parseValue(input: Any, graphQLContext: GraphQLContext, locale: Locale): Instant =
                (input as? String)?.let(::parseOrNull)
                    ?: throw CoercingParseValueException("Failed to parse \"DateTime\": input contains invalid characters")

            override fun parseLiteral(
                input: Value<*>, variables: CoercedVariables, graphQLContext: GraphQLContext, locale: Locale
            ): Instant = (input as? StringValue)?.value?.let(::parseOrNull)
                ?: throw CoercingParseLiteralException("Failed to parse \"DateTime\": input contains invalid characters")

            override fun valueToLiteral(input: Any, graphQLContext: GraphQLContext, locale: Locale): Value<*> =
                StringValue.newStringValue(if (input is Instant) toRfc3339(input) else input.toString()).build()
        })
        .build()
}
