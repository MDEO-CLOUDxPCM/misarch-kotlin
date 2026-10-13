package org.misarch.`return`.event.model

import java.util.UUID

/**
 * DTO for a shipment status update
 *
 * @property id id of the shipment
 * @property status new status of the shipment
 * @property orderId id of the order, if the shipment was created for an order (null/absent for return shipments)
 */
data class ShipmentStatusUpdatedDTO(
    val id: UUID,
    val status: ShipmentStatus,
    val orderId: UUID? = null
)
