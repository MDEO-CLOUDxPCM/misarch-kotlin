package org.misarch.simulation.model

import com.fasterxml.jackson.annotation.JsonIgnore
import java.util.UUID

/**
 * Body of `POST /payment/update`
 *
 * @property paymentId id of the payment
 * @property status status sent to the payment service
 */
data class UpdatePaymentDTO(
    val paymentId: UUID,
    override val status: String,
) : StatusUpdate {

    override val id: UUID
        @JsonIgnore get() = paymentId
}
