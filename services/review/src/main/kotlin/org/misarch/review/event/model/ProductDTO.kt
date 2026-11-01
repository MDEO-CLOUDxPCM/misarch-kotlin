package org.misarch.review.event.model

import java.util.UUID

/**
 * Product DTO used for events (only the fields this service reads)
 *
 * @property id id of the product
 */
data class ProductDTO(
    val id: UUID
)
