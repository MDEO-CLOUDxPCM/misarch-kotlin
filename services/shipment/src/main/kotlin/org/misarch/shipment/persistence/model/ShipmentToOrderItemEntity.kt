package org.misarch.shipment.persistence.model

import org.springframework.data.annotation.Id
import org.springframework.data.domain.Persistable
import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

/**
 * Join table for the many-to-many relationship between [ShipmentEntity] and [OrderItemEntity]; the primary key is the pair.
 *
 * @property key the shipment and the order item
 */
@Table
class ShipmentToOrderItemEntity(
    @Id
    val key: ShipmentToOrderItemKey
) : Persistable<ShipmentToOrderItemKey> {

    override fun getId(): ShipmentToOrderItemKey = key

    /** Rows are only inserted and deleted, never updated: `save` always inserts. */
    override fun isNew(): Boolean = true
}

/**
 * Primary key of [ShipmentToOrderItemEntity].
 *
 * @property shipmentId id of the shipment
 * @property orderItemId id of the order item
 */
data class ShipmentToOrderItemKey(val shipmentId: UUID, val orderItemId: UUID)
