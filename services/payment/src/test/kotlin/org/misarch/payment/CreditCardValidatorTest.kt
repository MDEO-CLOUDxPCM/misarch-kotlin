package org.misarch.payment

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.misarch.payment.service.CreditCardValidator
import java.time.LocalDate

/**
 * Card number, expiration date and masking rules of credit card payment informations.
 */
class CreditCardValidatorTest {

    private val today = LocalDate.of(2026, 10, 13)

    @ParameterizedTest(name = "card number \"{0}\" valid: {1}")
    @CsvSource(
        "4111111111111111, true",       // Visa
        "4111 1111 1111 1111, true",
        "4111-1111-1111-1111, true",
        "4012888888881881, true",
        "4222222222222, true",          // Visa, 13 digits
        "5105105105105100, true",       // Mastercard
        "2221000000000009, true",       // Mastercard 2-series
        "378282246310005, true",        // Amex
        "6011111111111117, true",       // Discover
        "30569309025904, true",         // Diners
        "3530111333300000, true",       // JCB
        "6200000000000005, true",       // UnionPay
        "4111111111111112, false",      // Luhn
        "5105a05105105100, false",
        "1234, false",
        "'', false",
        "0000000000000000, false",      // no issuer
        "9111111111111111, false",
        "5555555555554, false",         // Mastercard prefix, but 13 digits
    )
    fun `card numbers`(cardNumber: String, valid: Boolean) {
        assertThat(CreditCardValidator.isCreditCard(cardNumber)).isEqualTo(valid)
    }

    @ParameterizedTest(name = "expiration date \"{0}\" valid: {1}")
    @CsvSource(
        "12/30, true",
        "10/26, true",
        "1/30, true",
        "12/2030, true",
        "' 5/30', true",
        "5 / 30, true",
        "12/46, true",
        "09/26, false",                 // past month of the current year
        "12/25, false",                 // past year
        "12/47, false",                 // more than 20 years ahead
        "13/30, false",
        "00/30, false",
        "123/30, false",
        "12/300, false",
        "abc, false",
        "12, false",
        "12/, false",
        "/30, false",
        "'', false",
        "05-30, false",
        "05/30/99, false",
        "+5/30, false",
        "12/30abc, false",
        "1e1/30, false",
        "12/1930, false",
        "12/2000, false",
    )
    fun `expiration dates`(expirationDate: String, valid: Boolean) {
        assertThat(CreditCardValidator.isExpirationDate(expirationDate, today)).isEqualTo(valid)
    }

    @Test
    fun `validate reports every failed check`() {
        CreditCardValidator.validate("4111111111111111", "12/30", today)
        assertThatThrownBy { CreditCardValidator.validate("1234", "01/20", today) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("The credit card number is invalid, The expiration date is invalid or expired")
        assertThatThrownBy { CreditCardValidator.validate("4111111111111111", "abc", today) }
            .hasMessage("The expiration date is invalid or expired")
    }

    @Test
    fun `masking keeps the last four digits and the separators`() {
        assertThat(CreditCardValidator.mask("4111111111111111")).isEqualTo("************1111")
        assertThat(CreditCardValidator.mask("4111 1111 1111 1111")).isEqualTo("**** **** **** 1111")
        assertThat(CreditCardValidator.mask("4111-1111-1111-1111")).isEqualTo("****-****-****-1111")
        assertThat(CreditCardValidator.mask("1234")).isEqualTo("1234")
        assertThat(CreditCardValidator.mask("")).isEqualTo("")
    }
}
