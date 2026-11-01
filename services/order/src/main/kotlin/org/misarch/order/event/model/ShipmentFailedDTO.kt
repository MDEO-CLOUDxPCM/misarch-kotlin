package org.misarch.order.event.model

import java.util.UUID

/**
 * DTO for a shipment that could not be created (the fields order uses)
 *
 * @property orderId the id of the order, null for the shipment of a return
 * @property orderItemIds the ids of the order items of the shipment
 */
data class ShipmentFailedDTO(
    val orderId: UUID?,
    val orderItemIds: List<UUID>
)
