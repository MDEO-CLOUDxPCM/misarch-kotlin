package org.misarch.discount.persistence.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.util.*

/**
 * Join table for the many-to-many relationship between discounts and product variants
 *
 * @property discountId id of the discount
 * @property productVariantId id of the productVariant
 * @property id unique identifier of the join table row, technical requirement, not used in the domain
 */
@Table
class DiscountToProductVariantEntity(
    val discountId: UUID,
    val productVariantId: UUID,
    @Id
    val id: UUID?
)
