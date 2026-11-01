package org.misarch.simulation.payment.model

import java.util.UUID

/**
 * A registered payment, as returned by `POST /payment/findAll`
 *
 * @property id id of the payment
 * @property paymentType type of the payment
 * @property blocked true after a manual update
 */
data class PaymentDTO(
    val id: UUID,
    val paymentType: String,
    val blocked: Boolean,
)
