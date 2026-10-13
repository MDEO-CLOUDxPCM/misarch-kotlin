package org.misarch.common.nest

import graphql.GraphQLContext
import graphql.execution.CoercedVariables
import graphql.language.StringValue
import graphql.language.Value
import graphql.schema.Coercing
import graphql.schema.CoercingParseLiteralException
import graphql.schema.CoercingParseValueException
import graphql.schema.CoercingSerializeException
import graphql.schema.GraphQLScalarType
import java.util.Locale

/**
 * A UUID as the original handles it: a **string** (Mongo `_id` strings are compared case-sensitively; the scalar
 * returns the value unchanged, no normalisation).
 */
data class Uuid(val value: String) {
    override fun toString() = value
}

/**
 * The original's `CustomUuidScalar`: serialize/parseValue/parseLiteral check
 * `^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$` case-insensitively ("invalid uuid"); a non-string
 * literal fails with "UUID must be a string.".
 */
object UuidScalar {

    private val REGEX = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE)

    fun isValid(value: String) = REGEX.matches(value)

    val INSTANCE: GraphQLScalarType = GraphQLScalarType.newScalar()
        .name("UUID")
        .description("A universally unique identifier compliant UUID Scalar")
        .coercing(object : Coercing<Uuid, String> {
            override fun serialize(dataFetcherResult: Any, graphQLContext: GraphQLContext, locale: Locale): String {
                val value = when (dataFetcherResult) {
                    is Uuid -> dataFetcherResult.value
                    is String -> dataFetcherResult
                    else -> throw CoercingSerializeException("invalid uuid")
                }
                if (!isValid(value)) throw CoercingSerializeException("invalid uuid")
                return value
            }

            override fun parseValue(input: Any, graphQLContext: GraphQLContext, locale: Locale): Uuid {
                if (input is Uuid) return input
                if (input !is String || !isValid(input)) throw CoercingParseValueException("invalid uuid")
                return Uuid(input)
            }

            override fun parseLiteral(
                input: Value<*>, variables: CoercedVariables, graphQLContext: GraphQLContext, locale: Locale
            ): Uuid {
                if (input !is StringValue) throw CoercingParseLiteralException("UUID must be a string.")
                val value = input.value ?: ""
                if (!isValid(value)) throw CoercingParseLiteralException("invalid uuid")
                return Uuid(value)
            }

            override fun valueToLiteral(input: Any, graphQLContext: GraphQLContext, locale: Locale): Value<*> =
                StringValue.newStringValue(input.toString()).build()
        })
        .build()
}
