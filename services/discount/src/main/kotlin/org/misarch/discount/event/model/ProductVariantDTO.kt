package org.misarch.discount.event.model

import java.util.UUID

/**
 * ProductVariant DTO used for events
 *
 * @property id id of the productVariant
 * @property productId id of the product
 */
data class ProductVariantDTO(
    val id: UUID,
    val productId: UUID
)
