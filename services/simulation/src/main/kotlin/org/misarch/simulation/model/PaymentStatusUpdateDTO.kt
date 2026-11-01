package org.misarch.simulation.model

/**
 * Body of the payment status callback (`POST ${PAYMENT_URL}/payment/update-payment-status`)
 *
 * @property paymentId id of the payment
 * @property status new status of the payment
 */
data class PaymentStatusUpdateDTO(
    val paymentId: String,
    val status: String,
)
