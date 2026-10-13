package org.misarch.common.rust.asyncgraphql

import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.awaitBodyOrNull
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.buildAndAwait
import tools.jackson.core.JacksonException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigInteger
import kotlin.coroutines.cancellation.CancellationException
import org.slf4j.LoggerFactory
import java.util.UUID
import org.misarch.common.rust.RustUuid

/**
 * axum's `Json<T>` extractor + serde, as used by the Rust original's Dapr event handlers:
 * - `415` if `Content-Type` is not `application/json` or `application/<x>+json`,
 * - `400` if the body is not syntactically valid JSON,
 * - `422` if it does not deserialize into the target type (missing/ill-typed field, invalid UUID, negative count);
 *   unknown fields are ignored.
 * Bodies are plain text like axum's rejections (texts are not byte-identical to serde's messages).
 * Handlers answer `200 {"status":0}` (`TopicEventResponse::default()`) or `500` with an empty body.
 *
 * Used by the shoppingcart and wishlist ports. Not merged with [org.misarch.common.rust.AxumJson] (review, invoice):
 * that one checks the content type with `mime` rules, answers 413 above 2 MiB and 422 for duplicate keys, and has no
 * response bodies; this one uses Spring's media-type parsing, plain-text rejection bodies, and maps any other failure
 * (incl. a body above Spring's codec limit) to 500.
 */
object AxumJsonHandler {

    private val mapper: JsonMapper = JsonMapper.builder()
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()

    private val logger = LoggerFactory.getLogger(AxumJsonHandler::class.java)

    private val U64_MAX = BigInteger("18446744073709551615")

    /** Failure of the extractor or of the handler, mapped to the original's HTTP response. */
    class Rejection(val status: HttpStatus, val text: String) : RuntimeException(text)

    /**
     * Runs [handler] on the JSON body of [request] with axum's rejection semantics; [handler] reads the body with the
     * `require*` helpers (which throw [Rejection] `422`) and returns normally on success.
     */
    suspend fun handle(request: ServerRequest, handler: suspend (JsonNode) -> Unit): ServerResponse {
        return try {
            if (!isJsonContentType(request)) {
                throw Rejection(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Expected request with `Content-Type: application/json`")
            }
            val body = request.awaitBodyOrNull<ByteArray>() ?: ByteArray(0)
            val node = try {
                mapper.readTree(body)
            } catch (e: JacksonException) {
                throw Rejection(HttpStatus.BAD_REQUEST, "Failed to parse the request body as JSON: ${e.originalMessage}")
            }
            if (node == null || node.isMissingNode) {
                throw Rejection(HttpStatus.BAD_REQUEST, "Failed to parse the request body as JSON: EOF while parsing a value")
            }
            handler(node)
            ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValueAndAwait(SUCCESS)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (rejection: Rejection) {
            rejection.toResponse()
        } catch (e: Exception) {
            // e.g. a duplicate key: the original maps every database error to 500 (Dapr retries)
            logger.warn("event handler failed: {}", e.message)
            ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).buildAndAwait()
        }
    }

    private suspend fun Rejection.toResponse(): ServerResponse =
        if (text.isEmpty()) {
            ServerResponse.status(status).buildAndAwait()
        } else {
            ServerResponse.status(status).contentType(TEXT_PLAIN_UTF8).bodyValueAndAwait(text)
        }

    /** axum `json_content_type`: `application/json` or `application/<x>+json` (parameters allowed). */
    fun isJsonContentType(request: ServerRequest): Boolean {
        val header = request.headers().firstHeader("Content-Type") ?: return false
        val mediaType = try {
            MediaType.parseMediaType(header)
        } catch (e: IllegalArgumentException) {
            return false
        }
        return mediaType.type.equals("application", ignoreCase = true) &&
            (mediaType.subtype.equals("json", ignoreCase = true) || mediaType.subtype.lowercase().endsWith("+json"))
    }

    /** Handler failure (`StatusCode::INTERNAL_SERVER_ERROR`, empty body). */
    fun internalServerError(): Nothing = throw Rejection(HttpStatus.INTERNAL_SERVER_ERROR, "")

    private fun dataError(path: String, problem: String): Nothing =
        throw Rejection(HttpStatus.UNPROCESSABLE_ENTITY, "Failed to deserialize the JSON body into the target type: $path: $problem")

    /** The JSON object at [path] (serde struct). */
    fun requireObject(node: JsonNode?, path: String): JsonNode {
        if (node == null || node.isMissingNode) dataError(path, "missing field")
        if (!node.isObject) dataError(path, "invalid type, expected struct")
        return node
    }

    /** Required field [name] of a serde struct. */
    fun requireField(parent: JsonNode, name: String, path: String): JsonNode =
        parent.get(name) ?: dataError(path, "missing field `$name`")

    fun requireString(parent: JsonNode, name: String, path: String): String {
        val node = requireField(parent, name, path)
        if (!node.isString) dataError("$path.$name", "invalid type, expected a string")
        return node.asString()
    }

    /** `bson::Uuid` from a JSON string (Rust `uuid` parsing rules). */
    fun requireUuid(parent: JsonNode, name: String, path: String): UUID {
        val node = requireField(parent, name, path)
        if (!node.isString) dataError("$path.$name", "invalid type, expected a UUID")
        return RustUuid.tryParse(node.asString()) ?: dataError("$path.$name", "invalid UUID")
    }

    /** `u64` (non-negative JSON integer). */
    fun requireU64(parent: JsonNode, name: String, path: String): BigInteger {
        val node = requireField(parent, name, path)
        if (!node.isIntegralNumber) dataError("$path.$name", "invalid type, expected u64")
        val value = node.bigIntegerValue()
        if (value.signum() < 0 || value > U64_MAX) dataError("$path.$name", "invalid value, expected u64")
        return value
    }

    fun requireArray(parent: JsonNode, name: String, path: String): List<JsonNode> {
        val node = requireField(parent, name, path)
        if (!node.isArray) dataError("$path.$name", "invalid type, expected a sequence")
        return node.toList()
    }

    private const val SUCCESS = """{"status":0}"""
    private val TEXT_PLAIN_UTF8 = MediaType.parseMediaType("text/plain; charset=utf-8")
}
