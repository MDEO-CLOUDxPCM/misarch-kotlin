package org.misarch.payment.persistence.model

/**
 * The card data of a credit card payment information.
 *
 * @property cardHolder the name of the card holder
 * @property cardNumber the card number (masked in the public details)
 * @property expirationDate the expiration date (`MM/YY`)
 */
data class CreditCardDetails(
    val cardHolder: String,
    val cardNumber: String,
    val expirationDate: String
) {

    /** The details as the `JSONObject` of the GraphQL API. */
    fun toMap(): Map<String, Any?> = mapOf(
        "cardHolder" to cardHolder,
        "cardNumber" to cardNumber,
        "expirationDate" to expirationDate
    )

}
