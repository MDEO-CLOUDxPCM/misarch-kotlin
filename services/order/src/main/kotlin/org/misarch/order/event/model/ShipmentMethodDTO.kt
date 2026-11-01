package org.misarch.order.event.model

import java.util.UUID

/**
 * DTO for a created shipment method (only the id is used)
 *
 * @property id unique identifier of the shipment method
 */
data class ShipmentMethodDTO(
    val id: UUID
)
