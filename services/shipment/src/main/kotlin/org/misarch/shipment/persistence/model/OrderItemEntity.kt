package org.misarch.shipment.persistence.model

import org.misarch.common.persistence.BaseEntity
import org.misarch.shipment.graphql.model.OrderItem
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

/**
 * Entity for order items
 *
 * @param sentWithId unique identifier of the shipment the order item was sent with originally
 * @param productVariantVersionId unique identifier of the product variant version
 * @param quantity the quantity of the product variant version
 * @property id unique identifier of the order item
 */
@Table
class OrderItemEntity(
    val sentWithId: UUID,
    val productVariantVersionId: UUID,
    val quantity: Int,
    @Id
    override val id: UUID
) : BaseEntity<OrderItem> {

    override fun toDTO(): OrderItem {
        return OrderItem(id = id, sentWithId = sentWithId)
    }
}
