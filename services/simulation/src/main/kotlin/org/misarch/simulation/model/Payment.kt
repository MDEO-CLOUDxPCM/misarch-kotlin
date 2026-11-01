package org.misarch.simulation.model

import java.util.UUID

/**
 * A registered payment whose queue message has not been consumed yet
 *
 * @property id id of the payment
 * @property paymentType type of the payment
 * @property blocked true after a manual update: the queue message gets no automatic callback
 */
data class Payment(
    val id: UUID,
    val paymentType: String,
    val blocked: Boolean = false,
) {
    fun toDTO() = PaymentDTO(id, paymentType, blocked)
}
