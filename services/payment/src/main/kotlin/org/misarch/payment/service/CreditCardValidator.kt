package org.misarch.payment.service

import java.time.LocalDate
import java.time.YearMonth

/**
 * Validation and masking of credit card data.
 */
object CreditCardValidator {

    /** Years an expiration date may lie ahead. */
    private const val MAX_YEARS_AHEAD = 20

    private val SEPARATORS = Regex("[- ]+")

    /** `MM/YY` or `MM/YYYY` (month with one or two digits), whitespace allowed around the parts. */
    private val EXPIRATION_DATE = Regex("""\s*(\d{1,2})\s*/\s*(\d{2}|\d{4})\s*""")

    /**
     * Validates the card number and the expiration date.
     *
     * @throws IllegalArgumentException with the failed checks
     */
    fun validate(cardNumber: String, expirationDate: String, today: LocalDate) {
        val errors = listOfNotNull(
            "The credit card number is invalid".takeUnless { isValidCardNumber(cardNumber) },
            "The expiration date is invalid or expired".takeUnless { isValidExpirationDate(expirationDate, today) },
        )
        require(errors.isEmpty()) { errors.joinToString(", ") }
    }

    /**
     * Whether [cardNumber] (digits, optionally separated by spaces or dashes) is a number of an accepted
     * [CardNetwork] with a valid check digit.
     */
    fun isValidCardNumber(cardNumber: String): Boolean {
        val digits = cardNumber.replace(SEPARATORS, "")
        return CardNetwork.entries.any { it.numbers.matches(digits) } && hasValidCheckDigit(digits)
    }

    /**
     * Whether [expirationDate] is `MM/YY` or `MM/YYYY`, not before the month of [today] and in a year at most
     * [MAX_YEARS_AHEAD] years ahead.
     */
    fun isValidExpirationDate(expirationDate: String, today: LocalDate): Boolean {
        val (month, year) = EXPIRATION_DATE.matchEntire(expirationDate)?.destructured ?: return false
        if (month.toInt() !in 1..12) {
            return false
        }
        val expiration = YearMonth.of(if (year.length == 2) 2000 + year.toInt() else year.toInt(), month.toInt())
        return expiration >= YearMonth.from(today) && expiration.year <= today.year + MAX_YEARS_AHEAD
    }

    /** Masks every digit of a card number except the last four; separators are kept. */
    fun mask(cardNumber: String): String {
        val digitCount = cardNumber.count { it in '0'..'9' }
        var digitsSeen = 0
        return cardNumber.map { char ->
            if (char in '0'..'9' && ++digitsSeen <= digitCount - 4) '*' else char
        }.joinToString("")
    }

    /** The Luhn check of a digit string: every second digit from the right doubled, the sum divisible by 10. */
    private fun hasValidCheckDigit(digits: String): Boolean =
        digits.reversed().withIndex().sumOf { (index, char) ->
            val digit = char.digitToInt()
            if (index % 2 == 0) digit else (digit * 2).let { if (it > 9) it - 9 else it }
        } % 10 == 0

    /** The accepted card networks with the format of their card numbers (digits only). */
    private enum class CardNetwork(val numbers: Regex) {
        AMERICAN_EXPRESS(Regex("3[47]\\d{13}")),
        DINERS_CLUB(Regex("3(0[0-5]|[68]\\d)\\d{11}")),
        DISCOVER(Regex("6(011|5\\d{2})\\d{12,15}")),
        JCB(Regex("(2131|1800|35\\d{3})\\d{11}")),
        MASTERCARD(Regex("(5[1-5]\\d{2}|222[1-9]|22[3-9]\\d|2[3-6]\\d{2}|27[01]\\d|2720)\\d{12}")),
        UNION_PAY(Regex("6[27]\\d{14}|81\\d{14,17}")),
        VISA(Regex("4\\d{12}(\\d{3,6})?")),
    }

}
