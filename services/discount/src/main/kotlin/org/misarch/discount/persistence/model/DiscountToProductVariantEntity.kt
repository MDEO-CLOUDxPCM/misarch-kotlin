package org.misarch.discount.persistence.model

import org.springframework.data.annotation.Id
import org.springframework.data.domain.Persistable
import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

/**
 * Join table for the many-to-many relationship between discounts and product variants; the primary key is the pair.
 *
 * @property key the discount and the product variant
 */
@Table
class DiscountToProductVariantEntity(
    @Id
    val key: DiscountToProductVariantKey
) : Persistable<DiscountToProductVariantKey> {

    override fun getId(): DiscountToProductVariantKey = key

    /** Rows are only inserted and deleted, never updated: `save` always inserts. */
    override fun isNew(): Boolean = true
}

/**
 * Primary key of [DiscountToProductVariantEntity].
 *
 * @property discountId id of the discount
 * @property productVariantId id of the product variant
 */
data class DiscountToProductVariantKey(val discountId: UUID, val productVariantId: UUID)
