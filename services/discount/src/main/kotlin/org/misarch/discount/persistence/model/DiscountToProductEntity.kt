package org.misarch.discount.persistence.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.util.*

/**
 * Join table for the many-to-many relationship between discounts and products
 *
 * @property discountId id of the discount
 * @property productId id of the product
 * @property id unique identifier of the join table row, technical requirement, not used in the domain
 */
@Table
class DiscountToProductEntity(
    val discountId: UUID,
    val productId: UUID,
    @Id
    val id: UUID?
)
