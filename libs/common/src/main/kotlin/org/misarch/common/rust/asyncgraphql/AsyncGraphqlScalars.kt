package org.misarch.common.rust.asyncgraphql

import graphql.GraphQLContext
import graphql.execution.CoercedVariables
import graphql.language.StringValue
import graphql.language.Value
import graphql.schema.Coercing
import graphql.schema.CoercingParseLiteralException
import graphql.schema.CoercingParseValueException
import graphql.schema.CoercingSerializeException
import graphql.schema.GraphQLScalarType
import org.misarch.common.rust.RustUuid
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

/**
 * The `UUID` and `DateTime` scalars of the shoppingcart/wishlist ports (moved from their `compat/RustScalars.kt`
 * unchanged). They differ from [RustUuid.SCALAR] / [org.misarch.common.rust.RustDateTime.SCALAR] (review, invoice,
 * media) in their error texts (`Failed to parse "UUID": <input>` instead of the uuid crate's message), in the Kotlin
 * type of `DateTime` ([OffsetDateTime] instead of [Instant]) and in `DateTime` input parsing (strict
 * `OffsetDateTime.parse` instead of chrono's relaxed RFC 3339) - so both variants are kept.
 */
object AsyncGraphqlScalars {

    /** `UUID` scalar with Rust parsing ([RustUuid.tryParse]); output: lowercase hyphenated (like `Uuid::to_string`). */
    val UUID: GraphQLScalarType = GraphQLScalarType.newScalar()
        .name("UUID")
        .coercing(object : Coercing<UUID, String> {
            override fun serialize(dataFetcherResult: Any, graphQLContext: GraphQLContext, locale: Locale): String =
                when (dataFetcherResult) {
                    is UUID -> dataFetcherResult.toString()
                    is String -> RustUuid.tryParse(dataFetcherResult)?.toString()
                        ?: throw CoercingSerializeException("Invalid UUID: $dataFetcherResult")
                    else -> throw CoercingSerializeException("Expected a UUID but was ${dataFetcherResult.javaClass}")
                }

            override fun parseValue(input: Any, graphQLContext: GraphQLContext, locale: Locale): UUID =
                (input as? String)?.let(RustUuid::tryParse)
                    ?: (input as? UUID)
                    ?: throw CoercingParseValueException("Failed to parse \"UUID\": $input")

            override fun parseLiteral(
                input: Value<*>, variables: CoercedVariables, graphQLContext: GraphQLContext, locale: Locale
            ): UUID = (input as? StringValue)?.value?.let(RustUuid::tryParse)
                ?: throw CoercingParseLiteralException("Failed to parse \"UUID\": $input")

            override fun valueToLiteral(input: Any, graphQLContext: GraphQLContext, locale: Locale): Value<*> =
                StringValue.newStringValue(serialize(input, graphQLContext, locale)).build()
        })
        .build()

    /** `DateTime` scalar for [OffsetDateTime] (output [RustOffsetDateTime.format]; input RFC 3339 like chrono). */
    val DATE_TIME: GraphQLScalarType = GraphQLScalarType.newScalar()
        .name("DateTime")
        .coercing(object : Coercing<OffsetDateTime, String> {
            override fun serialize(dataFetcherResult: Any, graphQLContext: GraphQLContext, locale: Locale): String =
                when (dataFetcherResult) {
                    is OffsetDateTime -> RustOffsetDateTime.format(dataFetcherResult)
                    is Instant -> RustOffsetDateTime.format(dataFetcherResult.atOffset(ZoneOffset.UTC))
                    else -> throw CoercingSerializeException("Expected a DateTime but was ${dataFetcherResult.javaClass}")
                }

            override fun parseValue(input: Any, graphQLContext: GraphQLContext, locale: Locale): OffsetDateTime =
                parse(input.toString()) ?: throw CoercingParseValueException("Failed to parse \"DateTime\": $input")

            override fun parseLiteral(
                input: Value<*>, variables: CoercedVariables, graphQLContext: GraphQLContext, locale: Locale
            ): OffsetDateTime = (input as? StringValue)?.value?.let(::parse)
                ?: throw CoercingParseLiteralException("Failed to parse \"DateTime\": $input")

            override fun valueToLiteral(input: Any, graphQLContext: GraphQLContext, locale: Locale): Value<*> =
                StringValue.newStringValue(serialize(input, graphQLContext, locale)).build()
        })
        .build()

    private fun parse(value: String): OffsetDateTime? = try {
        OffsetDateTime.parse(value)
    } catch (e: DateTimeParseException) {
        null
    }
}

/**
 * `DateTime` of the Rust originals as [OffsetDateTime]: `bson::DateTime` (millisecond precision) written by
 * async-graphql via chrono's `DateTime<Utc>::to_rfc3339()` - `2026-10-13T08:15:42.123+00:00`, no fraction for whole
 * seconds, `+00:00` (not `Z`). (The [Instant] variant is [org.misarch.common.rust.RustDateTime]; its formatter
 * differs from [format] for years outside 0000-9999, so the two are not merged.)
 */
object RustOffsetDateTime {

    /** The current time as `bson::DateTime::now()` stores it (wall clock truncated to milliseconds, UTC). */
    fun now(): OffsetDateTime = Instant.now().truncatedTo(ChronoUnit.MILLIS).atOffset(ZoneOffset.UTC)

    /** chrono `to_rfc3339()` (`SecondsFormat::AutoSi`, `use_z = false`) of the instant in UTC. */
    fun format(value: OffsetDateTime): String {
        val utc = value.withOffsetSameInstant(ZoneOffset.UTC)
        val nanos = utc.nano
        val fraction = when {
            nanos == 0 -> ""
            nanos % 1_000_000 == 0 -> ".%03d".format(nanos / 1_000_000)
            nanos % 1_000 == 0 -> ".%06d".format(nanos / 1_000)
            else -> ".%09d".format(nanos)
        }
        return "%04d-%02d-%02dT%02d:%02d:%02d%s+00:00".format(
            utc.year, utc.monthValue, utc.dayOfMonth, utc.hour, utc.minute, utc.second, fraction
        )
    }
}
