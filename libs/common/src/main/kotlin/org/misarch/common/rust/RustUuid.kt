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
import java.util.Locale
import java.util.UUID

/**
 * UUID parsing exactly like the Rust originals (`bson::Uuid::parse_str` = uuid crate 1.x `Uuid::parse_str`):
 * accepts the hyphenated (36), simple (32 hex), braced (`{...}`, 38) and URN (`urn:uuid:...`, 45) forms in any case;
 * error texts are those of the uuid crate (`InvalidUuid::into_err`).
 */
object RustUuid {

    /** Parse failure with the uuid crate's error text. */
    class ParseException(message: String) : IllegalArgumentException(message)

    /** Parses [input] or throws [ParseException]. */
    fun parse(input: String): UUID {
        val bytes = input.toByteArray(Charsets.UTF_8)
        val hex: String? = when {
            bytes.size == 32 -> if (input.all(::isHex)) input else null
            bytes.size == 36 -> hyphenated(input)
            bytes.size == 38 && input.startsWith("{") && input.endsWith("}") -> hyphenated(input.substring(1, 37))
            bytes.size == 45 && input.startsWith("urn:uuid:") -> hyphenated(input.substring(9))
            else -> null
        }
        if (hex != null) {
            val msb = java.lang.Long.parseUnsignedLong(hex.substring(0, 16), 16)
            val lsb = java.lang.Long.parseUnsignedLong(hex.substring(16, 32), 16)
            return UUID(msb, lsb)
        }
        throw ParseException(errorMessage(input))
    }

    /** Parses [input] or returns null. */
    fun parseOrNull(input: String): UUID? = try {
        parse(input)
    } catch (e: ParseException) {
        null
    }

    /**
     * Parses [input] or returns null - never throws. Accepts exactly the forms of [parse] (simple, hyphenated,
     * braced, URN; hex digits in any case); the length checks use `String.length` (every valid input is ASCII).
     * The `parseOrNull`/`parseRustUuid` of the shoppingcart, wishlist and order ports (moved here unchanged).
     *
     * Not merged with [parseOrNull], which review/invoice/media use: [parse] checks UTF-8 byte lengths, and for a
     * braced input of 38 bytes but fewer than 37 chars (e.g. `{ää…}`) it fails with a
     * `StringIndexOutOfBoundsException` instead of [ParseException] / null. Kept as is (no behaviour change).
     */
    fun tryParse(input: String): UUID? {
        val hyphenated = when (input.length) {
            32 -> return parseHex(input)
            36 -> input
            38 -> if (input[0] == '{' && input[37] == '}') input.substring(1, 37) else return null
            45 -> if (input.startsWith("urn:uuid:")) input.substring(9) else return null
            else -> return null
        }
        if (hyphenated[8] != '-' || hyphenated[13] != '-' || hyphenated[18] != '-' || hyphenated[23] != '-') {
            return null
        }
        val hex = hyphenated.substring(0, 8) + hyphenated.substring(9, 13) + hyphenated.substring(14, 18) +
            hyphenated.substring(19, 23) + hyphenated.substring(24, 36)
        return parseHex(hex)
    }

    private fun parseHex(hex: String): UUID? {
        if (hex.length != 32 || hex.any { Character.digit(it, 16) < 0 || it.code > 127 }) {
            return null
        }
        return UUID(
            java.lang.Long.parseUnsignedLong(hex.substring(0, 16), 16),
            java.lang.Long.parseUnsignedLong(hex.substring(16, 32), 16)
        )
    }

    /**
     * Order of Rust `Uuid` (`Ord` = byte-wise) and of MongoDB BinData / Postgres `uuid`: unsigned lexicographic order
     * of the 16 bytes. (`java.util.UUID.compareTo` compares signed longs and differs for ids with the high bit set.)
     */
    val ORDER: Comparator<UUID> = Comparator { a, b ->
        val high = java.lang.Long.compareUnsigned(a.mostSignificantBits, b.mostSignificantBits)
        if (high != 0) high else java.lang.Long.compareUnsigned(a.leastSignificantBits, b.leastSignificantBits)
    }

    private fun isHex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    /** The 32 hex digits of a hyphenated uuid, or null. */
    private fun hyphenated(s: String): String? {
        if (s.length != 36) return null
        for ((i, c) in s.withIndex()) {
            val hyphen = i == 8 || i == 13 || i == 18 || i == 23
            if (hyphen && c != '-') return null
            if (!hyphen && !isHex(c)) return null
        }
        return s.replace("-", "")
    }

    /** Port of uuid 1.16 `InvalidUuid::into_err` + `Display for Error`. */
    fun errorMessage(input: String): String {
        val (uuidStr, offset, simple) = when {
            input.length >= 2 && input.startsWith("{") && input.endsWith("}") ->
                Triple(input.substring(1, input.length - 1), 1, false)
            input.startsWith("urn:uuid:") -> Triple(input.substring(9), 9, false)
            else -> Triple(input, 0, true)
        }
        var hyphenCount = 0
        val groupBounds = IntArray(4)
        var byteIndex = 0
        var i = 0
        while (i < uuidStr.length) {
            val cp = uuidStr.codePointAt(i)
            val ch = String(Character.toChars(cp))
            if (cp > 0x7F || (cp != '-'.code && !isHex(cp.toChar()))) {
                return "invalid character: expected an optional prefix of `urn:uuid:` followed by [0-9a-fA-F-], " +
                    "found `$ch` at ${byteIndex + offset + 1}"
            }
            if (cp == '-'.code) {
                if (hyphenCount < 4) groupBounds[hyphenCount] = byteIndex
                hyphenCount++
            }
            byteIndex += ch.toByteArray(Charsets.UTF_8).size
            i += Character.charCount(cp)
        }
        val inputLen = input.toByteArray(Charsets.UTF_8).size
        if (hyphenCount == 0 && simple) return "invalid length: expected length 32 for simple format, found $inputLen"
        if (hyphenCount != 4) return "invalid group count: expected 5, found ${hyphenCount + 1}"
        val starts = intArrayOf(0, 9, 14, 19, 24)
        val expected = intArrayOf(8, 4, 4, 4, 12)
        for (g in 0 until 4) {
            if (groupBounds[g] != starts[g + 1] - 1) {
                return "invalid group length in group $g: expected ${expected[g]}, found ${groupBounds[g] - starts[g]}"
            }
        }
        return "invalid group length in group 4: expected 12, found ${inputLen - starts[4]}"
    }

    /**
     * The `UUID` scalar of async-graphql's bson feature: output lowercase hyphenated, input parsed like [parse]
     * (error `Failed to parse "UUID": <uuid crate message>`).
     */
    val SCALAR: GraphQLScalarType = GraphQLScalarType.newScalar()
        .name("UUID")
        .coercing(object : Coercing<UUID, String> {
            override fun serialize(dataFetcherResult: Any, graphQLContext: GraphQLContext, locale: Locale): String =
                when (dataFetcherResult) {
                    is UUID -> dataFetcherResult.toString()
                    is String -> parseOrThrow(dataFetcherResult, ::CoercingSerializeException).toString()
                    else -> throw CoercingSerializeException("Expected a UUID, got ${dataFetcherResult::class.simpleName}")
                }

            override fun parseValue(input: Any, graphQLContext: GraphQLContext, locale: Locale): UUID =
                when (input) {
                    is UUID -> input
                    is String -> parseOrThrow(input, ::CoercingParseValueException)
                    else -> throw CoercingParseValueException("Expected input type \"UUID\", found $input.")
                }

            override fun parseLiteral(
                input: Value<*>, variables: CoercedVariables, graphQLContext: GraphQLContext, locale: Locale
            ): UUID =
                if (input is StringValue) parseOrThrow(input.value ?: "", ::CoercingParseLiteralException)
                else throw CoercingParseLiteralException("Expected input type \"UUID\", found $input.")

            override fun valueToLiteral(input: Any, graphQLContext: GraphQLContext, locale: Locale): Value<*> =
                StringValue.newStringValue(input.toString()).build()
        })
        .build()

    private fun <E : RuntimeException> parseOrThrow(s: String, error: (String) -> E): UUID = try {
        parse(s)
    } catch (e: ParseException) {
        throw error("Failed to parse \"UUID\": ${e.message}")
    }
}
