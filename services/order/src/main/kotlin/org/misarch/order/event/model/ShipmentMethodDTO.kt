package org.misarch.order.event.model

import java.util.UUID

/**
 * DTO for a created shipment method (the replica uses only the id; the fees are only read by the local copies,
 * docs/MECHANISMS.md)
 *
 * @property id unique identifier of the shipment method
 * @property baseFees the base fees
 * @property feesPerItem the fees per item
 * @property feesPerKg the fees per kg
 */
data class ShipmentMethodDTO(
    val id: UUID,
    val baseFees: Long? = null,
    val feesPerItem: Long? = null,
    val feesPerKg: Long? = null
)
