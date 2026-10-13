package org.misarch.common.nest

import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Small re-implementations of the JavaScript semantics the original (NestJS) code relies on: `typeof` checks on
 * JSON values, `String(x)`, `parseInt`, number formatting and `Date.prototype.toISOString`.
 */
object Js {

    val nodes: JsonNodeFactory = JsonNodeFactory.instance

    /** JavaScript whitespace (`String.prototype.trim`, `parseInt`). */
    fun isJsWhitespace(c: Char): Boolean = c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000B' ||
        c == '\u000C' || c == ' ' || c == ' ' || c in ' '..' ' || c == ' ' ||
        c == ' ' || c == ' ' || c == ' ' || c == '　' || c == '﻿'

    /** `parseInt(value, 10)`: null = NaN. */
    fun parseInt10(value: String): Long? {
        var i = 0
        while (i < value.length && isJsWhitespace(value[i])) i++
        var negative = false
        if (i < value.length && (value[i] == '+' || value[i] == '-')) {
            negative = value[i] == '-'
            i++
        }
        val start = i
        while (i < value.length && value[i] in '0'..'9') i++
        if (i == start) return null
        val digits = value.substring(start, i).trimStart('0').ifEmpty { "0" }
        val magnitude = if (digits.length > 18) Long.MAX_VALUE else digits.toLong()
        return if (negative) -magnitude else magnitude
    }

    /** JavaScript `Number.isInteger` / `typeof x === 'number'` on a JSON value. */
    fun isNumber(node: JsonNode?): Boolean = node != null && node.isNumber

    fun isInteger(node: JsonNode?): Boolean {
        if (node == null || !node.isNumber) return false
        if (node.isIntegralNumber) return true
        val d = node.asDouble()
        return d.isFinite() && d == Math.floor(d)
    }

    fun isString(node: JsonNode?): Boolean = node != null && node.isString

    /** `value != null && typeof value === 'object' && !Array.isArray(value)` (class-validator `isObject`). */
    fun isObject(node: JsonNode?): Boolean = node != null && node.isObject

    /** JavaScript `String(x)` of a JSON value (`undefined` for a missing value). */
    fun toJsString(node: JsonNode?): String = when {
        node == null || node.isMissingNode -> "undefined"
        node.isNull -> "null"
        node.isString -> node.asString()
        node.isBoolean -> node.asBoolean().toString()
        node.isNumber -> formatNumber(node.asDouble())
        node.isArray -> node.joinToString(",") { if (it.isNull) "" else toJsString(it) }
        else -> "[object Object]"
    }

    /** `Number.prototype.toString()` for the values that occur here (integral values without fraction). */
    fun formatNumber(value: Double): String = when {
        value.isNaN() -> "NaN"
        value.isInfinite() -> if (value > 0) "Infinity" else "-Infinity"
        value == Math.floor(value) && Math.abs(value) < 1e21 -> java.math.BigDecimal(value).toBigInteger().toString()
        else -> value.toString()
    }

    private val ISO: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    /** `Date.prototype.toISOString()` (milliseconds, `Z`). */
    fun toIsoString(instant: Instant): String = ISO.format(instant.truncatedTo(ChronoUnit.MILLIS))

    /** Current time with JavaScript `Date` precision (milliseconds). */
    fun now(): Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS)
}

/** Mongoose `castNumber` (used for `totalAmount`); throws like Mongoose on failure. */
object MongooseCast {
    fun number(node: JsonNode?, path: String, model: String): Double {
        if (node == null || node.isMissingNode || node.isNull) {
            throw JsError("$model validation failed: $path: Path `$path` is required.")
        }
        val value: Double? = when {
            node.isNumber -> node.asDouble()
            node.isBoolean -> if (node.asBoolean()) 1.0 else 0.0
            node.isString -> {
                val s = node.asString()
                if (s.isEmpty()) throw JsError("$model validation failed: $path: Path `$path` is required.")
                jsNumber(s)
            }
            else -> null
        }
        if (value == null || value.isNaN()) {
            throw JsError("$model validation failed: $path: Cast to Number failed for value \"${Js.toJsString(node)}\" (type ${typeName(node)}) at path \"$path\"")
        }
        return value
    }

    private fun typeName(node: JsonNode) = when {
        node.isString -> "string"
        node.isArray -> "Array"
        node.isObject -> "Object"
        else -> "unknown"
    }

    /** JavaScript `Number(string)`. */
    fun jsNumber(text: String): Double? {
        val s = text.trim { Js.isJsWhitespace(it) }
        if (s.isEmpty()) return 0.0
        val lower = s.lowercase()
        return try {
            when {
                lower.startsWith("0x") -> java.lang.Long.parseLong(s.substring(2), 16).toDouble()
                lower.startsWith("0b") -> java.lang.Long.parseLong(s.substring(2), 2).toDouble()
                lower.startsWith("0o") -> java.lang.Long.parseLong(s.substring(2), 8).toDouble()
                s == "Infinity" || s == "+Infinity" -> Double.POSITIVE_INFINITY
                s == "-Infinity" -> Double.NEGATIVE_INFINITY
                Regex("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$").matches(s) -> s.toDouble()
                else -> null
            }
        } catch (e: NumberFormatException) {
            null
        }
    }
}
