package org.misarch.simulation.js

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The JavaScript coercions reproduced from the original (expected values = what Node 18 prints). */
class JsTest {

    @Test
    fun `String(number) like ECMAScript`() {
        mapOf(
            0.0 to "0", -0.0 to "0", 5.0 to "5", 0.95 to "0.95", 100.0 to "100", 1000000.0 to "1000000",
            1e21 to "1e+21", 1.5e21 to "1.5e+21", 123456789012345680000.0 to "123456789012345680000",
            1e-7 to "1e-7", 0.000001 to "0.000001", 1.2345e-10 to "1.2345e-10", -2.5 to "-2.5",
            Double.NaN to "NaN", Double.POSITIVE_INFINITY to "Infinity", 0.1 + 0.2 to "0.30000000000000004",
        ).forEach { (value, expected) -> assertThat(Js.numberToString(value)).describedAs("$value").isEqualTo(expected) }
    }

    @Test
    fun `parseInt(s, 10) like ECMAScript`() {
        assertThat(Js.parseInt("5")).isEqualTo(5.0)
        assertThat(Js.parseInt("  -12abc")).isEqualTo(-12.0)
        assertThat(Js.parseInt("0.9")).isEqualTo(0.0)
        assertThat(Js.parseInt("1e+21")).isEqualTo(1.0)
        assertThat(Js.parseInt("+7")).isEqualTo(7.0)
        assertThat(Js.parseInt("abc")).isNaN()
        assertThat(Js.parseInt("")).isNaN()
        assertThat(Js.parseInt("null")).isNaN()
        assertThat(Js.parseInt("-")).isNaN()
    }

    @Test
    fun `Number(string) like ECMAScript`() {
        assertThat(Js.stringToNumber("")).isEqualTo(0.0)
        assertThat(Js.stringToNumber("  ")).isEqualTo(0.0)
        assertThat(Js.stringToNumber(" 3 ")).isEqualTo(3.0)
        assertThat(Js.stringToNumber("0.5")).isEqualTo(0.5)
        assertThat(Js.stringToNumber(".5")).isEqualTo(0.5)
        assertThat(Js.stringToNumber("5.")).isEqualTo(5.0)
        assertThat(Js.stringToNumber("1e3")).isEqualTo(1000.0)
        assertThat(Js.stringToNumber("0x10")).isEqualTo(16.0)
        assertThat(Js.stringToNumber("0b101")).isEqualTo(5.0)
        assertThat(Js.stringToNumber("-Infinity")).isEqualTo(Double.NEGATIVE_INFINITY)
        assertThat(Js.stringToNumber("3abc")).isNaN()
        assertThat(Js.stringToNumber("-0x10")).isNaN()
        assertThat(Js.stringToNumber("1_000")).isNaN()
        assertThat(Js.stringToNumber("NaN")).isNaN()
    }

    @Test
    fun `setTimeout clamps the delay to 1 ms outside 1 to 2^31-1`() {
        assertThat(Js.timeoutMillis(5000.0)).isEqualTo(5000)
        assertThat(Js.timeoutMillis(0.0)).isEqualTo(1)
        assertThat(Js.timeoutMillis(-1000.0)).isEqualTo(1)
        assertThat(Js.timeoutMillis(Double.NaN)).isEqualTo(1)
        assertThat(Js.timeoutMillis(2147483647.0)).isEqualTo(2147483647)
        assertThat(Js.timeoutMillis(2147483648.0)).isEqualTo(1)
    }

    @Test
    fun `JSON stringify keeps key order and formats like JavaScript`() {
        val node = JsJson.parse(
            """{"b":1.0,"a":[1e2,0.5,null,true],"s":"x\"\\\n\u0001ä😀","n":{"z":12345678901234567890}}"""
        )
        assertThat(JsJson.stringify(node))
            .isEqualTo("""{"b":1,"a":[100,0.5,null,true],"s":"x\"\\\n\u0001ä😀","n":{"z":12345678901234567000}}""")
    }

    @Test
    fun `JSON parse rejects what JSON parse rejects`() {
        listOf("", " ", "{", "{} x", "{'a':1}", "[1,]", "NaN").forEach { text ->
            assertThrows<IllegalArgumentException>(text) { JsJson.parse(text) }
        }
        assertThat(JsJson.parse("5").isNumber).isTrue()
        assertThat(JsJson.parse("null").isNull).isTrue()
    }

    @Test
    fun `truthiness String and Number of parsed values`() {
        val v = JsJson.parse("""{"s":"","t":"x","z":0,"o":2,"n":null,"b":false,"a":[],"arr":[5],"obj":{}}""")
        assertThat(listOf("s", "t", "z", "o", "n", "b", "a", "obj").map { JsJson.truthy(v[it]) })
            .containsExactly(false, true, false, true, false, false, true, true)
        assertThat(JsJson.truthy(v["missing"])).isFalse()
        assertThat(JsJson.toJsString(v["arr"])).isEqualTo("5")
        assertThat(JsJson.toJsString(v["obj"])).isEqualTo("[object Object]")
        assertThat(JsJson.toJsString(v["n"])).isEqualTo("null")
        assertThat(JsJson.toNumber(v["n"])).isEqualTo(0.0)
        assertThat(JsJson.toNumber(v["a"])).isEqualTo(0.0)
        assertThat(JsJson.toNumber(v["arr"])).isEqualTo(5.0)
        assertThat(JsJson.toNumber(v["b"])).isEqualTo(0.0)
        assertThat(JsJson.toNumber(v["obj"])).isNaN()
    }
}
