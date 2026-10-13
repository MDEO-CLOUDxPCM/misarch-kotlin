package org.misarch.shipment.event.model

import org.misarch.shipment.graphql.model.ShipmentStatus
import java.util.*

/**
 * Entity for the shipment created event
 * 
 * @property id id of the shipment
 * @property orderId id of the order, if the shipment is created for an order
 * @property returnId id of the return, if the shipment is created for a return
 * @property status status of the shipment (always PENDING)
 * @property orderItemIds ids of the order items that are part of the shipment
 * @property shipmentMethodId id of the shipment method
 */
data class ShipmentDTO(
    val id: UUID,
    val orderId: UUID?,
    val returnId: UUID?,
    val status: ShipmentStatus,
    val orderItemIds: List<UUID>,
    val shipmentMethodId: UUID,
    val shipmentAddressId: UUID
)