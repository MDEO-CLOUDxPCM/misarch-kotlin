package org.misarch.payment.service

import java.time.LocalDate

/**
 * Validation and masking of credit card data
 */
object CreditCardValidator {

    /** Card number patterns of the supported issuers (Amex, Diners, Discover, JCB, Mastercard, UnionPay, Visa) */
    private val CARD_NUMBER_PATTERNS = listOf(
        Regex("^3[47][0-9]{13}$"),
        Regex("^3(?:0[0-5]|[68][0-9])[0-9]{11}$"),
        Regex("^6(?:011|5[0-9][0-9])[0-9]{12,15}$"),
        Regex("^(?:2131|1800|35\\d{3})\\d{11}$"),
        Regex("^5[1-5][0-9]{2}|(222[1-9]|22[3-9][0-9]|2[3-6][0-9]{2}|27[01][0-9]|2720)[0-9]{12}$"),
        Regex("^(6[27][0-9]{14}|^(81[0-9]{14,17}))$"),
        Regex("^(?:4[0-9]{12})(?:[0-9]{3,6})?$"),
    )

    private val SEPARATORS = Regex("[- ]+")

    /** `MM/YY` or `MM/YYYY` (month with one or two digits), whitespace allowed around the parts */
    private val EXPIRATION_DATE = Regex("^\\s*(\\d{1,2})\\s*/\\s*(\\d{2}|\\d{4})\\s*$")

    /**
     * Whether [cardNumber] (digits, optionally separated by spaces or dashes) matches a supported issuer and has a
     * valid Luhn check digit
     */
    fun isCreditCard(cardNumber: String): Boolean {
        val digits = cardNumber.replace(SEPARATORS, "")
        return CARD_NUMBER_PATTERNS.any { it.containsMatchIn(digits) } && isLuhnNumber(digits)
    }

    /** Whether [digits] is a non-empty digit string with a valid Luhn check digit */
    fun isLuhnNumber(digits: String): Boolean {
        if (digits.isEmpty() || digits.any { it !in '0'..'9' }) {
            return false
        }
        val sum = digits.reversed().mapIndexed { index, c ->
            val digit = c - '0'
            if (index % 2 == 1) (digit * 2).let { if (it >= 10) it - 9 else it } else digit
        }.sum()
        return sum % 10 == 0
    }

    /**
     * Whether [expirationDate] is `MM/YY` or `MM/YYYY` with a month 1-12, not before the month of [today] and at most
     * 20 years ahead
     */
    fun isExpirationDate(expirationDate: String, today: LocalDate): Boolean {
        val match = EXPIRATION_DATE.matchEntire(expirationDate) ?: return false
        val month = match.groupValues[1].toInt()
        val year = match.groupValues[2].toInt().let { if (it > 2000) it - 2000 else it }
        val currentYear = today.year % 100
        return month in 1..12 &&
            year in currentYear..currentYear + 20 &&
            !(year == currentYear && month < today.monthValue)
    }

    /**
     * Validates the card number and the expiration date
     *
     * @throws IllegalArgumentException with the failed checks
     */
    fun validate(cardNumber: String, expirationDate: String, today: LocalDate) {
        val errors = listOfNotNull(
            "The credit card number is invalid".takeUnless { isCreditCard(cardNumber) },
            "The expiration date is invalid or expired".takeUnless { isExpirationDate(expirationDate, today) },
        )
        require(errors.isEmpty()) { errors.joinToString(", ") }
    }

    /** Masks every digit of a card number except the last four; separators are kept */
    fun mask(cardNumber: String): String {
        var digitsToMask = cardNumber.count { it in '0'..'9' } - 4
        return cardNumber.map { c ->
            if (c in '0'..'9' && digitsToMask-- > 0) '*' else c
        }.joinToString("")
    }

}
