package org.misarch.`return`.event.model

import java.util.UUID

/**
 * Entity for the shipment created event (only the fields used by the return service; the rest is ignored)
 *
 * @property id id of the shipment
 * @property orderId id of the order, if the shipment is created for an order
 * @property status status of the shipment (always PENDING)
 * @property orderItemIds ids of the order items that are part of the shipment
 */
data class ShipmentDTO(
    val id: UUID,
    val orderId: UUID?,
    val status: ShipmentStatus,
    val orderItemIds: List<UUID>,
)
