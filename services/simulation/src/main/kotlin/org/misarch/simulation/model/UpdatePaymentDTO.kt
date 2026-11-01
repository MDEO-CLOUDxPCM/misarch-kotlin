package org.misarch.simulation.model

import java.util.UUID

/**
 * Body of `POST /payment/update`
 *
 * @property paymentId id of the payment
 * @property status status sent to the payment service
 */
data class UpdatePaymentDTO(
    val paymentId: UUID,
    val status: String,
)
