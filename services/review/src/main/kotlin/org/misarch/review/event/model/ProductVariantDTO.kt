package org.misarch.review.event.model

import java.util.UUID

/**
 * Product variant DTO used for events (only the fields this service reads)
 *
 * @property id id of the product variant
 * @property productId id of the product the variant belongs to
 */
data class ProductVariantDTO(
    val id: UUID,
    val productId: UUID
)
