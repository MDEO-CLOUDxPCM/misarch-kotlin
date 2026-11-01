package org.misarch.shipment.event.model

/**
 * Payment authorization data.
 *
 * @property cvc CVC/CVV number of 3-4 digits.
 */
data class PaymentAuthorizationDTO(
    val cvc: Int
)
