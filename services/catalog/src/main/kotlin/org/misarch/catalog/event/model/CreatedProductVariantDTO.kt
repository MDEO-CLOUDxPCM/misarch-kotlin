package org.misarch.catalog.event.model

import java.util.UUID

/**
 * Product variant DTO used for events
 *
 * @property id unique identifier of the product variant
 * @property productId id of the referenced product
 * @property currentVersionId id of the current version of the product variant
 * @property isPubliclyVisible whether the product variant is publicly visible
 */
data class CreatedProductVariantDTO(
    val id: UUID,
    val productId: UUID,
    val currentVersionId: UUID,
    val isPubliclyVisible: Boolean
)