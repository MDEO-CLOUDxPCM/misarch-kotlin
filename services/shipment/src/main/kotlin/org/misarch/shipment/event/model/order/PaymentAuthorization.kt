package org.misarch.shipment.event.model.order

/**
 * Payment authorization data.
 *
 * @property cvc CVC/CVV number of 3-4 digits.
 */
data class PaymentAuthorization(
    val cvc: Int
)