package org.misarch.common.nest

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

/**
 * Unit tests of the NestJS/JavaScript emulation shared by the inventory and payment ports (texts verified against the
 * running originals; moved from inventory's `NestCompatTest` and payment's `PaymentUnitTest`).
 */
class NestCompatTest {

    private val mapper = JsonMapper.builder().build()

    @Test
    fun `guard denies missing and empty headers`() {
        assertThatThrownBy { RolesGuard.check(null as String?, "admin") }.isInstanceOf(ForbiddenException::class.java)
            .hasMessage("Forbidden resource")
        assertThatThrownBy { RolesGuard.check("", "admin") }.isInstanceOf(ForbiddenException::class.java)
    }

    @Test
    fun `guard checks role membership`() {
        RolesGuard.check("""{"id":"x","roles":["employee"]}""", "employee", "admin")
        assertThatThrownBy { RolesGuard.check("""{"id":"x","roles":["buyer"]}""", "employee", "admin") }
            .isInstanceOf(ForbiddenException::class.java)
    }

    @Test
    fun `guard uses substring semantics for a string role (quirk)`() {
        RolesGuard.check("""{"id":"x","roles":"superadmin"}""", "employee", "admin")
    }

    @Test
    fun `guard reproduces the JavaScript errors`() {
        assertThatThrownBy { RolesGuard.check("""{"id":"x"}""", "admin") }.isInstanceOf(JsError::class.java)
            .hasMessage("Cannot read properties of undefined (reading 'includes')")
        assertThatThrownBy { RolesGuard.check("notjson", "admin") }.isInstanceOf(JsError::class.java)
            .hasMessage("Unexpected token o in JSON at position 1")
        assertThatThrownBy { RolesGuard.check("""{"roles":5}""", "admin") }
            .hasMessage("roles.includes is not a function")
        assertThatThrownBy { RolesGuard.check("null", "admin") }
            .hasMessage("Cannot read properties of null (reading 'roles')")
        assertThatThrownBy { RolesGuard.check("{", "admin") }.hasMessage("Unexpected end of JSON input")
    }

    @Test
    fun `uuid scalar accepts any case and rejects other formats`() {
        assertThat(UuidScalar.isValid("0b0e2a3c-1111-4222-8333-444455556666")).isTrue()
        assertThat(UuidScalar.isValid("0B0E2A3C-1111-4222-8333-444455556666")).isTrue()
        assertThat(UuidScalar.isValid("1-1-1-1-1")).isFalse()
        assertThat(UuidScalar.isValid("{0b0e2a3c-1111-4222-8333-444455556666}")).isFalse()
    }

    @Test
    fun `parseInt and number formatting follow JavaScript`() {
        assertThat(Js.parseInt10(" 12abc")).isEqualTo(12)
        assertThat(Js.parseInt10("+5")).isEqualTo(5)
        assertThat(Js.parseInt10("-5")).isEqualTo(-5)
        assertThat(Js.parseInt10("abc")).isNull()
        assertThat(Js.parseInt10("")).isNull()
        assertThat(Js.parseInt10("0x10")).isEqualTo(0)
        assertThat(Js.formatNumber(1999.0)).isEqualTo("1999")
        assertThat(Js.formatNumber(19.99)).isEqualTo("19.99")
    }

    @Test
    fun `Mongoose number cast of the order amount`() {
        assertThat(MongooseCast.number(mapper.readTree("1999"), "totalAmount", "Payment")).isEqualTo(1999.0)
        assertThat(MongooseCast.number(mapper.readTree("\"12\""), "totalAmount", "Payment")).isEqualTo(12.0)
        assertThat(MongooseCast.number(mapper.readTree("true"), "totalAmount", "Payment")).isEqualTo(1.0)
        assertThatThrownBy { MongooseCast.number(null, "totalAmount", "Payment") }
            .hasMessage("Payment validation failed: totalAmount: Path `totalAmount` is required.")
        assertThatThrownBy { MongooseCast.number(mapper.readTree("\"abc\""), "totalAmount", "Payment") }.isInstanceOf(JsError::class.java)
    }
}
