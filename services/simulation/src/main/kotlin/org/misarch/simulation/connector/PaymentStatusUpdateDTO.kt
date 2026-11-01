package org.misarch.simulation.connector

/**
 * Body of the payment callback
 *
 * @property paymentId id of the payment
 * @property status new status of the payment
 */
data class PaymentStatusUpdateDTO(
    val paymentId: String,
    val status: String,
)
