package org.misarch.order.client.model

import java.util.UUID

/**
 * The inventory count of a product variant, as returned by the inventory service
 *
 * @property id unique identifier of the product variant
 * @property inventoryCount the number of unreserved product items
 */
data class ProductVariantInventoryDTO(
    val id: UUID,
    val inventoryCount: Long
)
