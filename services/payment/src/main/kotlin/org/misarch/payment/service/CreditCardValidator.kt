package org.misarch.payment.service

import java.time.LocalDate

/**
 * Validation and masking of credit card data.
 */
object CreditCardValidator {

    /** Card number formats of the accepted issuers. */
    private val ISSUER_FORMATS = mapOf(
        "American Express" to Regex("3[47][0-9]{13}"),
        "Diners Club" to Regex("3(?:0[0-5]|[68][0-9])[0-9]{11}"),
        "Discover" to Regex("6(?:011|5[0-9]{2})[0-9]{12,15}"),
        "JCB" to Regex("(?:2131|1800|35[0-9]{3})[0-9]{11}"),
        "Mastercard" to Regex("(?:5[1-5][0-9]{2}|222[1-9]|22[3-9][0-9]|2[3-6][0-9]{2}|27[01][0-9]|2720)[0-9]{12}"),
        "UnionPay" to Regex("6[27][0-9]{14}|81[0-9]{14,17}"),
        "Visa" to Regex("4[0-9]{12}(?:[0-9]{3,6})?"),
    )

    private val SEPARATORS = Regex("[- ]+")

    /** `MM/YY` or `MM/YYYY` (month with one or two digits), whitespace allowed around the parts. */
    private val EXPIRATION_DATE = Regex("\\s*(\\d{1,2})\\s*/\\s*(\\d{2}|\\d{4})\\s*")

    /** Years an expiration date may lie ahead. */
    private const val MAX_YEARS_AHEAD = 20

    /**
     * Whether [cardNumber] (digits, optionally separated by spaces or dashes) has the format of an accepted issuer
     * and a valid Luhn check digit.
     */
    fun isCreditCard(cardNumber: String): Boolean {
        val digits = cardNumber.replace(SEPARATORS, "")
        return ISSUER_FORMATS.values.any { it.matches(digits) } && hasValidCheckDigit(digits)
    }

    /** Whether [digits] is a non-empty digit string with a valid Luhn check digit. */
    fun hasValidCheckDigit(digits: String): Boolean {
        if (digits.isEmpty() || !digits.all(::isDigit)) {
            return false
        }
        val sum = digits.reversed().withIndex().sumOf { (index, char) ->
            val digit = char - '0'
            when {
                index % 2 == 0 -> digit
                digit < 5 -> digit * 2
                else -> digit * 2 - 9
            }
        }
        return sum % 10 == 0
    }

    /**
     * Whether [expirationDate] is `MM/YY` or `MM/YYYY` with a month 1-12, not before the month of [today] and at most
     * [MAX_YEARS_AHEAD] years ahead.
     */
    fun isExpirationDate(expirationDate: String, today: LocalDate): Boolean {
        val (monthText, yearText) = EXPIRATION_DATE.matchEntire(expirationDate)?.destructured ?: return false
        val month = monthText.toInt()
        val year = if (yearText.length == 4) yearText.toInt() - 2000 else yearText.toInt()
        val currentYear = today.year % 100
        return month in 1..12 &&
            year in currentYear..currentYear + MAX_YEARS_AHEAD &&
            !(year == currentYear && month < today.monthValue)
    }

    /**
     * Validates the card number and the expiration date.
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

    /** Masks every digit of a card number except the last four; separators are kept. */
    fun mask(cardNumber: String): String {
        val firstVisibleDigit = cardNumber.indices.filter { isDigit(cardNumber[it]) }.takeLast(4).firstOrNull()
            ?: return cardNumber
        return cardNumber.mapIndexed { index, char -> if (index < firstVisibleDigit && isDigit(char)) '*' else char }
            .joinToString("")
    }

    private fun isDigit(char: Char): Boolean = char in '0'..'9'

}
