package org.misarch.order.event.model

/**
 * DTO of the payment authorization of a placed order
 *
 * @property cvc the CVC/CVV number
 */
data class PaymentAuthorizationDTO(
    val cvc: Int
)
