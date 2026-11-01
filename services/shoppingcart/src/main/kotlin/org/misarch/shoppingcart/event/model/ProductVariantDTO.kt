package org.misarch.shoppingcart.event.model

import java.util.UUID

/**
 * Product variant DTO used for events (only the fields this service reads)
 *
 * @property id id of the product variant
 */
data class ProductVariantDTO(
    val id: UUID
)
