package org.misarch.payment

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.misarch.payment.graphql.CardValidation
import org.misarch.payment.nest.JsDate
import org.misarch.common.nest.JsError
import org.misarch.payment.persistence.Sort
import org.misarch.payment.service.sortOf
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
    fun `sort without defaults reproduces Mongoose`() {
        assertThat(sortOf(null)).isEqualTo(Sort.ID_ASC)
        assertThat(sortOf(mapOf("field" to "ID", "direction" to "DESC"))).isEqualTo(Sort.ID_DESC)
        assertThat(sortOf(mapOf("direction" to "DESC"))).isEqualTo(Sort.NATURAL)
        assertThatThrownBy { sortOf(mapOf("field" to "ID")) }.isInstanceOf(JsError::class.java)
            .hasMessage("Invalid sort value: { _id: undefined }")
        assertThatThrownBy { sortOf(emptyMap()) }.hasMessage("Invalid sort value: { undefined: undefined }")
    }

    @Test
    fun `credit card mask and Luhn`() {
        assertThat("4111111111111111".replace(Regex("\\d(?=\\d{4})"), "*")).isEqualTo("************1111")
        assertThat("4111 1111 1111 1111".replace(Regex("\\d(?=\\d{4})"), "*")).isEqualTo("4111 1111 1111 1111")
        assertThat(CardValidation.isCreditCard("4111-1111-1111-1111")).isTrue()
        assertThat(CardValidation.isCreditCard("4111111111111112")).isFalse()
        assertThat(CardValidation.isCreditCard("5105a05105105100")).isFalse()
    }
}
