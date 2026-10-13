package org.misarch.order.graphql.scalar

import graphql.GraphQLContext
import graphql.execution.CoercedVariables
import graphql.language.StringValue
import graphql.language.Value
import graphql.schema.Coercing
import graphql.schema.CoercingParseLiteralException
import graphql.schema.CoercingParseValueException
import graphql.schema.CoercingSerializeException
import graphql.schema.GraphQLScalarType
import org.misarch.common.graphql.ScalarMapping
import org.misarch.order.rust.RustError
import org.misarch.common.rust.RustDateTime
import org.misarch.common.rust.RustUuid
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Instant
import java.util.Locale
import java.util.UUID

/**
 * Scalars with the behaviour of the async-graphql/bson scalars of the original:
 * - `DateTime` ([Instant]): output = chrono `to_rfc3339()` (`2026-10-13T12:00:00.123+00:00`; the order service
 *   has no DateTime inputs),
 * - `UUID`: output lower-case hyphenated; input accepts the forms of `uuid::Uuid::parse_str` (hyphenated, simple,
 *   braced, URN).
 */
@Configuration(proxyBeanMethods = false)
class ScalarConfiguration {

    @Bean
    fun dateTimeScalar() = ScalarMapping(Instant::class, DATE_TIME)

    @Bean
    fun uuidScalar() = ScalarMapping(UUID::class, RUST_UUID)

    companion object {
        val DATE_TIME: GraphQLScalarType = GraphQLScalarType.newScalar()
            .name("DateTime")
            .coercing(object : Coercing<Instant, String> {
                override fun serialize(dataFetcherResult: Any, graphQLContext: GraphQLContext, locale: Locale): String =
                    when (dataFetcherResult) {
                        is Instant -> RustDateTime.toRfc3339(dataFetcherResult)
                        else -> throw CoercingSerializeException("Expected an Instant, got $dataFetcherResult")
                    }

                override fun parseValue(input: Any, graphQLContext: GraphQLContext, locale: Locale): Instant =
                    parse(input.toString()) ?: throw CoercingParseValueException("Failed to parse \"DateTime\"")

                override fun parseLiteral(
                    input: Value<*>, variables: CoercedVariables, graphQLContext: GraphQLContext, locale: Locale
                ): Instant = (input as? StringValue)?.value?.let { parse(it) }
                    ?: throw CoercingParseLiteralException("Failed to parse \"DateTime\"")

                private fun parse(value: String): Instant? = try {
                    java.time.OffsetDateTime.parse(value).toInstant()
                } catch (e: Exception) {
                    null
                }
            })
            .build()

        val RUST_UUID: GraphQLScalarType = GraphQLScalarType.newScalar()
            .name("UUID")
            .coercing(object : Coercing<UUID, String> {
                override fun serialize(dataFetcherResult: Any, graphQLContext: GraphQLContext, locale: Locale): String =
                    when (dataFetcherResult) {
                        is UUID -> dataFetcherResult.toString()
                        is String -> RustUuid.tryParse(dataFetcherResult)?.toString()
                            ?: throw CoercingSerializeException("Failed to parse \"UUID\"")
                        else -> throw CoercingSerializeException("Failed to parse \"UUID\"")
                    }

                override fun parseValue(input: Any, graphQLContext: GraphQLContext, locale: Locale): UUID =
                    when (input) {
                        is UUID -> input
                        is String -> RustUuid.tryParse(input)
                        else -> null
                    } ?: throw CoercingParseValueException("Failed to parse \"UUID\": invalid UUID `$input`")

                override fun parseLiteral(
                    input: Value<*>, variables: CoercedVariables, graphQLContext: GraphQLContext, locale: Locale
                ): UUID = (input as? StringValue)?.value?.let { RustUuid.tryParse(it) }
                    ?: throw CoercingParseLiteralException("Failed to parse \"UUID\": invalid UUID `$input`")
            })
            .build()
    }
}

/**
 * async-graphql's unsigned integer scalars (`usize`, `u32`, `u64`, `u16` are all GraphQL `Int`): negative values are
 * rejected with `Invalid number`, values above the Rust type's maximum with `Only integers from 0 to <max> are
 * accepted.`. [suffix] is async-graphql's `(occurred while parsing "<Input>")` chain for nested input fields.
 */
fun unsignedArgument(value: Int?, max: Long = Long.MAX_VALUE, suffix: String = ""): Int? {
    if (value == null) return null
    if (value < 0) throw RustError("Failed to parse \"Int\": Invalid number$suffix")
    if (value > max) throw RustError("Failed to parse \"Int\": Only integers from 0 to $max are accepted.$suffix")
    return value
}
