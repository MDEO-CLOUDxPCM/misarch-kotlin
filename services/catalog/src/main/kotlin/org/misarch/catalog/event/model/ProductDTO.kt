package org.misarch.catalog.event.model

import java.util.UUID

/**
 * Product DTO used for events
 *
 * @property id unique identifier of the product
 * @property internalName internal name of the product
 * @property isPubliclyVisible whether the product is publicly visible
 * @property defaultVariantId id of the default variant of the product
 * @property categoryIds ids of the referenced categories
 */
data class ProductDTO(
    val id: UUID,
    val internalName: String,
    val isPubliclyVisible: Boolean,
    val defaultVariantId: UUID,
    val categoryIds: List<UUID>
)