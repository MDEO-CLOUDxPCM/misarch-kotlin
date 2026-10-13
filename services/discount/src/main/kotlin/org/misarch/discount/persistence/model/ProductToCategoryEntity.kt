package org.misarch.discount.persistence.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.util.*

/**
 * Join table for the many-to-many relationship between products and categories
 *
 * @property productId id of the product
 * @property categoryId id of the category
 * @property id unique identifier of the join table row, technical requirement, not used in the domain
 */
@Table
class ProductToCategoryEntity(
    val productId: UUID,
    val categoryId: UUID,
    @Id
    val id: UUID?
)
