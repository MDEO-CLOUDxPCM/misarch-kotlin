package org.misarch.order.client.model

import java.util.UUID

/**
 * An item of a shopping cart, as returned by the shoppingcart service
 *
 * @property id unique identifier of the shopping cart item
 * @property productVariant the product variant of the item
 * @property count the quantity
 */
data class ShoppingCartItemDTO(
    val id: UUID,
    val productVariant: ProductVariantReferenceDTO,
    val count: Long
)

/**
 * A reference to a product variant
 *
 * @property id unique identifier of the product variant
 */
data class ProductVariantReferenceDTO(
    val id: UUID
)
