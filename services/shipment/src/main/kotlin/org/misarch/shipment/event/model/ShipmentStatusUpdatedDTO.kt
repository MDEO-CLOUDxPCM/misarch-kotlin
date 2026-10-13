package org.misarch.shipment.event.model

import org.misarch.shipment.graphql.model.ShipmentStatus
import java.util.UUID

/**
 * DTO for a shipment status update
 *
 * @property id id of the shipment
 * @property status new status of the shipment
 * @property orderId id of the order, if the shipment is created for an order
 * @property returnId id of the return, if the shipment is created for a return
 * @property orderItemIds ids of the order items that are part of the shipment
 */
data class ShipmentStatusUpdatedDTO(
    val id: UUID,
    val orderItemIds: List<UUID>,
    val orderId: UUID?,
    val returnId: UUID?,
    val status: ShipmentStatus
)