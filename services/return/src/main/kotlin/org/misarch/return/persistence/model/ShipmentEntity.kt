package org.misarch.`return`.persistence.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Entity for a shipment (replica, fed by `shipment/shipment/created` and `shipment/shipment/status-updated`)
 *
 * @property deliveredAt the date and time the shipment was delivered, null if not delivered
 * @property id unique identifier of the shipment
 */
@Table
class ShipmentEntity(
    var deliveredAt: OffsetDateTime?,
    @Id
    val id: UUID
)
