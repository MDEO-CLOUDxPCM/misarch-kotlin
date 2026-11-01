package org.misarch.simulation.model

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import java.util.UUID

/**
 * Body of `POST /shipment/register`; further properties (`ref`, `quantity`, `weight`, `address`, ...) are kept and
 * relayed to the queue.
 *
 * @property shipmentId id of the shipment
 * @property additionalProperties further properties of the request
 */
data class RegisterShipmentDTO(
    val shipmentId: UUID,
    @param:JsonAnySetter
    @get:JsonAnyGetter
    val additionalProperties: Map<String, Any?> = emptyMap(),
)
