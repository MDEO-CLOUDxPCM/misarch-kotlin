package org.misarch.invoice.event.model

/**
 * DTO of the payment authorization of an order
 *
 * @property cvc the CVC/CVV number
 */
data class PaymentAuthorizationDTO(
    val cvc: Int
)
