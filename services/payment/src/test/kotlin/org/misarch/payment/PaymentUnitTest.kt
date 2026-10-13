package org.misarch.payment

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.misarch.payment.graphql.CardValidation
import org.misarch.payment.nest.JsDate
import org.misarch.payment.persistence.Sort
import org.misarch.payment.service.maskCardNumber
import org.misarch.payment.service.sortOf
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Unit tests of the payment-specific JavaScript/Mongoose emulation (the shared `MongooseCast` is tested in libs/common). */
class PaymentUnitTest {

    @Test
    fun `JavaScript Date parsing of ISO strings`() {
        assertThat(JsDate.parse("2020-01-01", ZoneOffset.ofHours(2)).instant).isEqualTo(Instant.parse("2020-01-01T00:00:00Z"))
        assertThat(JsDate.parse("2020-01-01T10:00:00", ZoneOffset.ofHours(2)).instant).isEqualTo(Instant.parse("2020-01-01T08:00:00Z"))
        assertThat(JsDate.parse("2099-01-01T00:00:00+02:00").instant).isEqualTo(Instant.parse("2098-12-31T22:00:00Z"))
        assertThat(JsDate.parse("2026-10-13T12:00:00.123456Z").instant).isEqualTo(Instant.parse("2026-10-13T12:00:00.123Z"))
        assertThat(JsDate.parse("2026-10").instant).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"))
        assertThat(JsDate.parse("garbage").isValid).isFalse()
        assertThat(JsDate.parse("2026-13-01").isValid).isFalse()
    }

    @Test
    fun `sort defaults to ID ascending`() {
        assertThat(sortOf(null)).isEqualTo(Sort.ID_ASC)
        assertThat(sortOf(mapOf("field" to "ID", "direction" to "DESC"))).isEqualTo(Sort.ID_DESC)
        assertThat(sortOf(mapOf("direction" to "DESC"))).isEqualTo(Sort.ID_DESC)
        assertThat(sortOf(mapOf("field" to "ID"))).isEqualTo(Sort.ID_ASC)
        assertThat(sortOf(emptyMap())).isEqualTo(Sort.ID_ASC)
    }

    @Test
    fun `credit card mask, Luhn and expiration format`() {
        assertThat(maskCardNumber("4111111111111111")).isEqualTo("************1111")
        assertThat(maskCardNumber("4111 1111 1111 1111")).isEqualTo("**** **** **** 1111")
        assertThat(maskCardNumber("4111-1111-1111-1111")).isEqualTo("****-****-****-1111")
        assertThat(maskCardNumber("1234")).isEqualTo("1234")
        assertThat(maskCardNumber("")).isEqualTo("")
        assertThat(CardValidation.isCreditCard("4111-1111-1111-1111")).isTrue()
        assertThat(CardValidation.isCreditCard("4111111111111112")).isFalse()
        assertThat(CardValidation.isCreditCard("5105a05105105100")).isFalse()
        val clock = Clock.fixed(Instant.parse("2026-10-13T12:00:00Z"), ZoneOffset.UTC)
        for (valid in listOf("12/30", "10/26", "1/30", "12/2030", " 5/30", "5 / 30", "12/46")) {
            assertThat(CardValidation.isExpirationDate(valid, clock)).describedAs(valid).isTrue()
        }
        for (invalid in listOf("abc", "12", "12/", "/30", "", "05-30", "05/30/99", "+5/30", "12/30abc", "09/26", "12/47", "13/30", "00/30", "123/30", "12/300")) {
            assertThat(CardValidation.isExpirationDate(invalid, clock)).describedAs(invalid).isFalse()
        }
    }
}
