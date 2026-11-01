package org.misarch.simulation.shipment.model

import java.util.UUID

/**
 * Body of `POST /shipment/update`
 *
 * @property shipmentId id of the shipment
 * @property status status sent to the shipment service
 */
data class UpdateShipmentDTO(
    val shipmentId: UUID,
    val status: String,
)
