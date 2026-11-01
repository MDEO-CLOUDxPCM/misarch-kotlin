package org.misarch.simulation.shipment.model

import java.util.UUID

/**
 * A registered shipment, as returned by `POST /shipment/findAll`
 *
 * @property id id of the shipment
 * @property blocked true after a manual update
 */
data class ShipmentDTO(
    val id: UUID,
    val blocked: Boolean,
)
