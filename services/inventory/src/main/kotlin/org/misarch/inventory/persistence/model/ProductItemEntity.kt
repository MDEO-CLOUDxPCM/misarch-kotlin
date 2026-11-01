package org.misarch.inventory.persistence.model

import org.misarch.common.persistence.BaseEntity
import org.misarch.inventory.graphql.model.ProductItem
import org.misarch.inventory.graphql.model.ProductItemStatus
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

/**
 * Entity for product items (one row per physical item in stock). The table also has an `insertionOrder` column
 * (set by the database, used by the reservation to take the oldest items first), which is not mapped.
 *
 * @property productVariantId the product variant of the item
 * @property inventoryStatus the inventory status of the item
 * @property orderId the order that reserved the item, if any
 * @property id unique identifier of the product item
 */
@Table
class ProductItemEntity(
    var productVariantId: UUID,
    var inventoryStatus: ProductItemStatus,
    var orderId: UUID?,
    @Id
    override val id: UUID?
) : BaseEntity<ProductItem> {

    override fun toDTO(): ProductItem {
        return ProductItem(
            id = id!!,
            productVariantId = productVariantId,
            inventoryStatus = inventoryStatus,
            orderId = orderId
        )
    }

}
