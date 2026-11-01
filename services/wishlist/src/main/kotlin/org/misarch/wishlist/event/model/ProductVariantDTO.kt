package org.misarch.wishlist.event.model

import java.util.UUID

/**
 * ProductVariant DTO used for events (only the fields this service reads)
 *
 * @property id id of the product variant
 */
data class ProductVariantDTO(
    val id: UUID
)
