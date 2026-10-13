package org.misarch.simulation.js

import java.math.BigDecimal
import java.math.BigInteger

/**
 * The JavaScript coercion rules the original (NestJS, Node 18) simulation relies on, reproduced exactly:
 * `String(number)`, `Number(string)`, `parseInt(string, 10)` and Node's `setTimeout` delay clamp.
 */
object Js {

    /** Largest delay Node's `setTimeout` accepts (2^31 - 1 ms); larger, smaller than 1 or NaN delays become 1 ms. */
    const val TIMEOUT_MAX = 2147483647.0

    /** ECMAScript `Number::toString(10)` (e.g. `1e21` -> `"1e+21"`, `1e-7` -> `"1e-7"`, `100.0` -> `"100"`). */
    fun numberToString(d: Double): String {
        if (d.isNaN()) return "NaN"
        if (d == Double.POSITIVE_INFINITY) return "Infinity"
        if (d == Double.NEGATIVE_INFINITY) return "-Infinity"
        if (d == 0.0) return "0"
        if (d < 0) return "-" + numberToString(-d)
        // JDK >= 19: Double.toString yields the shortest digits that round-trip, as ECMAScript requires
        val bd = BigDecimal(java.lang.Double.toString(d)).stripTrailingZeros()
        val digits = bd.unscaledValue().toString()
        val k = digits.length
        val n = k - bd.scale() // value = 0.<digits> * 10^n
        return when {
            n in k..21 -> digits + "0".repeat(n - k)
            n in 1..21 -> digits.substring(0, n) + "." + digits.substring(n)
            n in -5..0 -> "0." + "0".repeat(-n) + digits
            else -> {
                val e = n - 1
                val exponent = if (e >= 0) "e+$e" else "e$e"
                if (k == 1) digits + exponent else digits[0] + "." + digits.substring(1) + exponent
            }
        }
    }

    /** ECMAScript `StringToNumber` (`Number("...")`, also used by `<`/`<=` between a number and a string). */
    fun stringToNumber(s: String): Double {
        val t = s.trim { isWhitespace(it) }
        if (t.isEmpty()) return 0.0
        when (t) {
            "Infinity", "+Infinity" -> return Double.POSITIVE_INFINITY
            "-Infinity" -> return Double.NEGATIVE_INFINITY
        }
        if (t.length > 2 && t[0] == '0') {
            val radix = when (t[1]) {
                'x', 'X' -> 16
                'o', 'O' -> 8
                'b', 'B' -> 2
                else -> 0
            }
            if (radix != 0) {
                val body = t.substring(2)
                return if (body.all { Character.digit(it, radix) >= 0 }) BigInteger(body, radix).toDouble() else Double.NaN
            }
        }
        return if (DECIMAL_LITERAL.matches(t)) t.toDouble() else Double.NaN
    }

    /** `parseInt(s, 10)`: optional leading whitespace and sign, then the longest run of decimal digits. */
    fun parseInt(s: String): Double {
        var i = 0
        while (i < s.length && isWhitespace(s[i])) i++
        var sign = 1.0
        if (i < s.length && (s[i] == '+' || s[i] == '-')) {
            if (s[i] == '-') sign = -1.0
            i++
        }
        val start = i
        while (i < s.length && s[i] in '0'..'9') i++
        if (i == start) return Double.NaN
        return sign * BigDecimal(s.substring(start, i)).toDouble()
    }

    /** Node's `setTimeout(fn, after)`: `after` outside [1, 2^31-1] (or NaN) becomes 1 ms. */
    fun timeoutMillis(after: Double): Long = if (after >= 1.0 && after <= TIMEOUT_MAX) after.toLong() else 1L

    private val DECIMAL_LITERAL = Regex("""[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?""")

    /** ECMAScript WhiteSpace + LineTerminator. */
    private fun isWhitespace(c: Char): Boolean =
        c == '\u0009' || c == '\u000B' || c == '\u000C' || c == ' ' || c == ' ' || c == '﻿' ||
            c == '\n' || c == '\r' || c == ' ' || c == ' ' ||
            Character.getType(c) == Character.SPACE_SEPARATOR.toInt()
}
