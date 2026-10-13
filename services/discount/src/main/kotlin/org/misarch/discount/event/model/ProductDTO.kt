package org.misarch.discount.event.model

import java.util.*

/**
 * Product DTO used for events
 *
 * @property id id of the product
 * @property categoryIds list of category ids
 */
data class ProductDTO(
    val id: UUID,
    val categoryIds: List<UUID>
)
