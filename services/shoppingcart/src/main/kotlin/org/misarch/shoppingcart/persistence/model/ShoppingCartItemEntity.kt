package org.misarch.shoppingcart.persistence.model

import org.misarch.common.persistence.BaseEntity
import org.misarch.common.persistence.requiredId
import org.misarch.shoppingcart.graphql.model.ProductVariant
import org.misarch.shoppingcart.graphql.model.ShoppingCartItem
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Entity for shopping cart items
 *
 * @property userId id of the user owning the shopping cart
 * @property count number of pieces of the product variant
 * @property addedAt when the item was added to the cart
 * @property productVariantId id of the product variant
 * @property id unique identifier of the item
 */
@Table
class ShoppingCartItemEntity(
    val userId: UUID,
    var count: Long,
    val addedAt: OffsetDateTime,
    val productVariantId: UUID,
    @Id
    override val id: UUID?
) : BaseEntity<ShoppingCartItem> {

    override fun toDTO(): ShoppingCartItem {
        return ShoppingCartItem(
            id = requiredId,
            count = count.toInt(),
            addedAt = addedAt,
            productVariant = ProductVariant(productVariantId),
            userId = userId
        )
    }

}
