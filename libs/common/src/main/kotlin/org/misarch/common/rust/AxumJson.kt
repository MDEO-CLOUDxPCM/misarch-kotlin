package org.misarch.common.rust

import tools.jackson.core.exc.StreamReadException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigInteger
import java.time.Instant
import java.util.UUID

/**
 * Request-body handling of axum's `Json<T>` extractor + serde, as used by the Rust originals' Dapr event routes
 * (`Json<Event<T>>`). Status codes are Dapr's retry contract, so they are reproduced exactly:
 * - no/other content type → **415** (accepted: `application/json`, `application/(any)+json`, parameters allowed),
 * - body > 2 MiB → **413**, empty or syntactically invalid JSON (incl. trailing characters) → **400**,
 * - valid JSON not matching the target type (missing/mistyped field, bad uuid, unknown enum variant, duplicate key)
 *   → **422** (throw [DataError] from the typed accessors below),
 * - unknown fields are ignored (serde default).
 *
 * ```
 * val root = when (val r = AxumJson.parse(contentType, body)) { is AxumJson.Rejected -> return status(r.status); is AxumJson.Parsed -> r.root }
 * val event = AxumJson.decode { val data = root.reqObject("data"); root.reqString("topic") to data.reqUuid("id") } ?: return status(422)
 * ```
 */
object AxumJson {

    /** axum's default body limit (`DefaultBodyLimit`, 2 MiB). */
    const val BODY_LIMIT: Int = 2 * 1024 * 1024

    sealed interface Result
    data class Parsed(val root: JsonNode) : Result
    data class Rejected(val status: Int) : Result

    /** serde data error (→ 422). */
    class DataError(message: String) : RuntimeException(message)

    private val mapper: JsonMapper = JsonMapper.builder()
        .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()

    /** Content-type check, size limit and JSON syntax check. */
    fun parse(contentType: String?, body: ByteArray?): Result {
        if (RustMime.parse(contentType)?.isJson != true) return Rejected(415)
        val bytes = body ?: ByteArray(0)
        if (bytes.size > BODY_LIMIT) return Rejected(413)
        if (bytes.all { it == ' '.code.toByte() || it == '\n'.code.toByte() || it == '\r'.code.toByte() || it == '\t'.code.toByte() }) {
            return Rejected(400)
        }
        return try {
            Parsed(mapper.readTree(bytes))
        } catch (e: StreamReadException) {
            Rejected(400)
        } catch (e: RuntimeException) {
            // duplicate keys are a data error for serde (struct fields), trailing tokens a syntax error
            if (e.message?.contains("Trailing token", ignoreCase = true) == true) Rejected(400) else Rejected(422)
        }
    }

    /** Runs typed extraction; null if it failed with a [DataError] (→ answer 422). */
    inline fun <T> decode(block: () -> T): T? = try {
        block()
    } catch (e: DataError) {
        null
    }

    private fun JsonNode.fieldOrNull(name: String): JsonNode? {
        if (!isObject) throw DataError("invalid type: expected struct")
        val node = get(name)
        return if (node == null || node.isMissingNode) null else node
    }

    /** Required field of any JSON type except null. */
    fun JsonNode.req(name: String): JsonNode {
        val node = fieldOrNull(name) ?: throw DataError("missing field `$name`")
        if (node.isNull) throw DataError("invalid type: null for `$name`")
        return node
    }

    /** `String` field. */
    fun JsonNode.reqString(name: String): String {
        val node = req(name)
        if (!node.isString) throw DataError("invalid type for `$name`, expected a string")
        return node.stringValue()
    }

    /** `Option<String>` field (missing or null → null). */
    fun JsonNode.optString(name: String): String? {
        val node = fieldOrNull(name) ?: return null
        if (node.isNull) return null
        if (!node.isString) throw DataError("invalid type for `$name`, expected a string")
        return node.stringValue()
    }

    /** `bson::Uuid` field (a string parsed like [RustUuid]). */
    fun JsonNode.reqUuid(name: String): UUID = asUuid(req(name), name)

    /** `Option<bson::Uuid>` field. */
    fun JsonNode.optUuid(name: String): UUID? {
        val node = fieldOrNull(name) ?: return null
        return if (node.isNull) null else asUuid(node, name)
    }

    /** Element of a `Vec<Uuid>`. */
    fun asUuid(node: JsonNode, name: String): UUID {
        if (!node.isString) throw DataError("invalid type for `$name`, expected a UUID")
        return RustUuid.parseOrNull(node.stringValue()) ?: throw DataError("invalid UUID for `$name`")
    }

    /** `bool` field. */
    fun JsonNode.reqBool(name: String): Boolean {
        val node = req(name)
        if (!node.isBoolean) throw DataError("invalid type for `$name`, expected a boolean")
        return node.booleanValue()
    }

    /** Unsigned integer field with the given maximum (`u16`, `u32`, `u64`): integral JSON number in range. */
    fun JsonNode.reqUnsigned(name: String, max: BigInteger = U64_MAX): BigInteger {
        val node = req(name)
        if (!node.isIntegralNumber) throw DataError("invalid type for `$name`, expected an unsigned integer")
        val value = node.bigIntegerValue()
        if (value.signum() < 0 || value > max) throw DataError("invalid value for `$name`")
        return value
    }

    /** `DateTime<Utc>` field (chrono's relaxed RFC 3339). */
    fun JsonNode.reqDateTime(name: String): Instant {
        val node = req(name)
        if (!node.isString) throw DataError("invalid type for `$name`, expected a date-time string")
        return RustDateTime.parseOrNull(node.stringValue()) ?: throw DataError("invalid date-time for `$name`")
    }

    /** Nested struct field. */
    fun JsonNode.reqObject(name: String): JsonNode {
        val node = req(name)
        if (!node.isObject) throw DataError("invalid type for `$name`, expected a struct")
        return node
    }

    /** `Vec<T>` field. */
    fun JsonNode.reqArray(name: String): List<JsonNode> {
        val node = req(name)
        if (!node.isArray) throw DataError("invalid type for `$name`, expected a sequence")
        return node.toList()
    }

    val U16_MAX: BigInteger = BigInteger.valueOf(65535)
    val U32_MAX: BigInteger = BigInteger.valueOf(4294967295L)
    val U64_MAX: BigInteger = BigInteger("18446744073709551615")
}
