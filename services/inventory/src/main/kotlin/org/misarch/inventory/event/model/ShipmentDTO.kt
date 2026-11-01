package org.misarch.inventory.event.model

import java.util.UUID

/**
 * A created or updated shipment (only the order and the status are used)
 *
 * @property orderId the order of the shipment, null for a return shipment
 * @property status the status of the shipment
 */
data class ShipmentDTO(
    val orderId: UUID?,
    val status: ShipmentStatus,
)

/**
 * Status of a shipment
 */
enum class ShipmentStatus {
    PENDING,
    IN_PROGRESS,
    DELIVERED,
    FAILED,
}
