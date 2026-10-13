package org.misarch.simulation.js

import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

/**
 * JSON with JavaScript semantics, as seen by the original: `JSON.parse` (via Jackson trees, key order kept),
 * `JSON.stringify` (numbers formatted like JS, JS string escaping) and the coercions `String(v)`, `Number(v)` and
 * truthiness on parsed values.
 */
object JsJson {

    private val mapper: JsonMapper = JsonMapper.builder()
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()

    val nodes: JsonNodeFactory = JsonNodeFactory.instance

    fun objectNode(): ObjectNode = nodes.objectNode()

    fun arrayNode(): ArrayNode = nodes.arrayNode()

    /** `JSON.parse(text)`; throws [IllegalArgumentException] for anything `JSON.parse` would reject. */
    fun parse(text: String): JsonNode {
        if (text.isBlank()) throw IllegalArgumentException("Unexpected end of JSON input")
        val node = try {
            mapper.readTree(text)
        } catch (e: Exception) {
            throw IllegalArgumentException(e.message, e)
        }
        if (node == null || node.isMissingNode) throw IllegalArgumentException("Unexpected end of JSON input")
        return node
    }

    /** `JSON.stringify(node)`. */
    fun stringify(node: JsonNode): String = StringBuilder().also { write(it, node) }.toString()

    private fun write(out: StringBuilder, node: JsonNode) {
        when {
            node.isNull || node.isMissingNode -> out.append("null")
            node.isBoolean -> out.append(if (node.booleanValue()) "true" else "false")
            node.isNumber -> {
                val d = node.doubleValue()
                // JSON.stringify writes non-finite numbers as null
                out.append(if (d.isFinite()) Js.numberToString(d) else "null")
            }
            node.isString -> writeString(out, node.stringValue())
            node.isArray -> {
                out.append('[')
                node.values().forEachIndexed { i, element ->
                    if (i > 0) out.append(',')
                    write(out, element)
                }
                out.append(']')
            }
            node.isObject -> {
                out.append('{')
                node.properties().forEachIndexed { i, (name, value) ->
                    if (i > 0) out.append(',')
                    writeString(out, name)
                    out.append(':')
                    write(out, value)
                }
                out.append('}')
            }
            else -> writeString(out, node.asString())
        }
    }

    /** JSON.stringify's QuoteJSONString (ES2019 well-formed: lone surrogates escaped, lower-case hex). */
    private fun writeString(out: StringBuilder, s: String) {
        out.append('"')
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\b' -> out.append("\\b")
                c == '\u000C' -> out.append("\\f")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c < ' ' -> out.append("\\u").append(String.format("%04x", c.code))
                Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> {
                    out.append(c).append(s[i + 1])
                    i++
                }
                Character.isSurrogate(c) -> out.append("\\u").append(String.format("%04x", c.code))
                else -> out.append(c)
            }
            i++
        }
        out.append('"')
    }

    /** JavaScript truthiness of a parsed value (`null`/missing → false). */
    fun truthy(node: JsonNode?): Boolean = when {
        node == null || node.isNull || node.isMissingNode -> false
        node.isBoolean -> node.booleanValue()
        node.isNumber -> node.doubleValue().let { it != 0.0 && !it.isNaN() }
        node.isString -> node.stringValue().isNotEmpty()
        else -> true // objects and arrays
    }

    /** `String(v)` (also what template literals `${v}` produce). */
    fun toJsString(node: JsonNode?): String = when {
        node == null || node.isMissingNode -> "undefined"
        node.isNull -> "null"
        node.isBoolean -> if (node.booleanValue()) "true" else "false"
        node.isNumber -> Js.numberToString(node.doubleValue())
        node.isString -> node.stringValue()
        node.isArray -> node.values().joinToString(",") { if (it.isNull) "" else toJsString(it) }
        else -> "[object Object]"
    }

    /** `Number(v)`. */
    fun toNumber(node: JsonNode?): Double = when {
        node == null || node.isMissingNode -> Double.NaN
        node.isNull -> 0.0
        node.isBoolean -> if (node.booleanValue()) 1.0 else 0.0
        node.isNumber -> node.doubleValue()
        node.isString -> Js.stringToNumber(node.stringValue())
        node.isArray -> Js.stringToNumber(toJsString(node))
        else -> Double.NaN
    }
}
