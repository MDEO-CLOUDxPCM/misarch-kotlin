package org.misarch.catalog.event.model

import java.util.*

/**
 * Product variant DTO used for events
 *
 * @property id unique identifier of the product variant
 * @property isPubliclyVisible whether the product variant is publicly visible
 */
data class UpdatedProductVariantDTO(
    val id: UUID,
    val isPubliclyVisible: Boolean
)