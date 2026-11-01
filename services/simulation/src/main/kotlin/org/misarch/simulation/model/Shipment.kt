package org.misarch.simulation.model

import java.util.UUID

/**
 * A registered shipment whose queue message has not been consumed yet
 *
 * @property id id of the shipment
 * @property blocked true after a manual update: the queue message gets no automatic callback
 */
data class Shipment(
    val id: UUID,
    val blocked: Boolean = false,
) {
    fun toDTO() = ShipmentDTO(id, blocked)
}
