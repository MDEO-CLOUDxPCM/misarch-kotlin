package org.misarch.`return`.event.model

import java.util.UUID

/**
 * Product variant version DTO used for events
 *
 * @property id unique identifier of the product variant version
 * @property canBeReturnedForDays number of days the product variant version can be returned, null if it cannot be returned
 */
data class ProductVariantVersionDTO(
    val id: UUID,
    val canBeReturnedForDays: Int?,
)
