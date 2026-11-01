package org.misarch.simulation.model

import com.fasterxml.jackson.annotation.JsonIgnore
import java.util.UUID

/**
 * Body of `POST /shipment/update`
 *
 * @property shipmentId id of the shipment
 * @property status status sent to the shipment service
 */
data class UpdateShipmentDTO(
    val shipmentId: UUID,
    override val status: String,
) : StatusUpdate {

    override val id: UUID
        @JsonIgnore get() = shipmentId
}
