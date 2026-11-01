package org.misarch.simulation.model

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnore
import java.util.UUID

/**
 * Body of `POST /payment/register`; further properties are kept and relayed to the queue.
 *
 * @property paymentId id of the payment
 * @property amount amount of the payment
 * @property paymentType type of the payment (`prepayment`, `invoice`, `credit-card`)
 * @property additionalProperties further properties of the request
 */
data class RegisterPaymentDTO(
    val paymentId: UUID,
    val amount: Double,
    val paymentType: String,
    @param:JsonAnySetter
    @get:JsonAnyGetter
    val additionalProperties: Map<String, Any?> = emptyMap(),
) : Registration {

    override val id: UUID
        @JsonIgnore get() = paymentId
}
