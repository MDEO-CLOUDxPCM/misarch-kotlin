package org.misarch.discount.persistence.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.util.*

/**
 * Join table for the many-to-many relationship between discounts and categories
 *
 * @property discountId id of the discount
 * @property categoryId id of the category
 * @property id unique identifier of the join table row, technical requirement, not used in the domain
 */
@Table
class DiscountToCategoryEntity(
    val discountId: UUID,
    val categoryId: UUID,
    @Id
    val id: UUID?
)
