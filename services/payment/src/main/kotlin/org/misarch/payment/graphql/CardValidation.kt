package org.misarch.payment.graphql

import org.misarch.common.nest.ClassValidator
import org.misarch.common.nest.ClassValidator.Property
import org.misarch.common.nest.ClassValidator.Schema
import org.misarch.common.nest.Js
import java.time.Clock
import java.time.LocalDate

/**
 * Validation of `CreateCreditCardInformationInput`: `@IsCreditCard({message})` (validator.js 13.11) and the
 * original's custom `@IsExpirationDate()`.
 */
object CardValidation {

    /** validator.js 13.11 provider patterns, applied with `RegExp.test` (unanchored where the original is). */
    private val CARDS = listOf(
        Regex("^3[47][0-9]{13}$"),
        Regex("^3(?:0[0-5]|[68][0-9])[0-9]{11}$"),
        Regex("^6(?:011|5[0-9][0-9])[0-9]{12,15}$"),
        Regex("^(?:2131|1800|35\\d{3})\\d{11}$"),
        Regex("^5[1-5][0-9]{2}|(222[1-9]|22[3-9][0-9]|2[3-6][0-9]{2}|27[01][0-9]|2720)[0-9]{12}$"),
        Regex("^(6[27][0-9]{14}|^(81[0-9]{14,17}))$"),
        Regex("^(?:4[0-9]{12})(?:[0-9]{3,6})?$"),
    )
    private val SEPARATORS = Regex("[- ]+")

    fun isCreditCard(card: String): Boolean {
        val sanitized = card.replace(SEPARATORS, "")
        if (CARDS.none { it.containsMatchIn(sanitized) }) return false
        return isLuhnNumber(card)
    }

    /** validator.js `isLuhnNumber` (a non-digit makes the sum NaN → false). */
    fun isLuhnNumber(value: String): Boolean {
        val sanitized = value.replace(SEPARATORS, "")
        var sum = 0
        var shouldDouble = false
        for (i in sanitized.length - 1 downTo 0) {
            val c = sanitized[i]
            if (c !in '0'..'9') return false
            var digit = c - '0'
            if (shouldDouble) {
                digit *= 2
                sum += if (digit >= 10) digit % 10 + 1 else digit
            } else {
                sum += digit
            }
            shouldDouble = !shouldDouble
        }
        return sanitized.isNotEmpty() && sum % 10 == 0
    }

    /**
     * The original `IsExpirationDate` validator: `[month, year] = value.split('/').map(v => parseInt(v, 10))`;
     * comparisons with NaN/undefined are false, so unparsable values pass.
     */
    fun isExpirationDate(value: String, clock: Clock): Boolean {
        val parts = value.split("/")
        val month: Long? = Js.parseInt10(parts[0])
        val year: Long? = if (parts.size > 1) Js.parseInt10(parts[1]) else null
        val now = LocalDate.now(clock)
        val currentYear = (now.year % 100).toLong()
        val currentMonth = now.monthValue.toLong()
        val adjustedYear: Long? = if (year != null && year > 2000) year - 2000 else year
        if (adjustedYear != null && (adjustedYear < currentYear || adjustedYear > currentYear + 20)) return false
        if (adjustedYear != null && adjustedYear == currentYear && month != null && month < currentMonth) return false
        if (month != null && (month < 1 || month > 12)) return false
        return true
    }

    fun schema(clock: Clock) = Schema(
        listOf(
            Property("cardHolder"),
            Property("cardNumber", listOf(ClassValidator.custom("isCreditCard", "The credit card number is invalid") {
                Js.isString(it) && isCreditCard(it!!.asString())
            })),
            Property("expirationDate", listOf(ClassValidator.custom("isExpirationDateValid", "The expiration date is invalid or expired") {
                Js.isString(it) && isExpirationDate(it!!.asString(), clock)
            })),
        )
    )
}
