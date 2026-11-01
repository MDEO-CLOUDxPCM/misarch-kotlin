package org.misarch.order.persistence.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

/**
 * Entity for shipment methods (replica, fed by `shipment/shipment-method/created`)
 *
 * @property id unique identifier of the shipment method
 */
@Table
class ShipmentMethodEntity(
    @Id
    val id: UUID
)
