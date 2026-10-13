package org.misarch.shipment.persistence.model

import org.springframework.data.annotation.Id
import java.util.UUID

/**
 * Join table for the many-to-many relationship between [ShipmentEntity] and [OrderItemEntity]
 *
 * @property shipmentId unique identifier of the shipment
 * @property orderItemId unique identifier of the order item
 * @property id unique identifier of the join table row, technical requirement, not used in the domain
 */
class ShipmentToOrderItemEntity(
    val shipmentId: UUID,
    val orderItemId: UUID,
    @Id
    val id: UUID?
)
