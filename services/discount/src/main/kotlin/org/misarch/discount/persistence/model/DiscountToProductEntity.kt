package org.misarch.discount.persistence.model

import org.springframework.data.annotation.Id
import org.springframework.data.domain.Persistable
import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

/**
 * Join table for the many-to-many relationship between discounts and products; the primary key is the pair.
 *
 * @property key the discount and the product
 */
@Table
class DiscountToProductEntity(
    @Id
    val key: DiscountToProductKey
) : Persistable<DiscountToProductKey> {

    override fun getId(): DiscountToProductKey = key

    /** Rows are only inserted and deleted, never updated: `save` always inserts. */
    override fun isNew(): Boolean = true
}

/**
 * Primary key of [DiscountToProductEntity].
 *
 * @property discountId id of the discount
 * @property productId id of the product
 */
data class DiscountToProductKey(val discountId: UUID, val productId: UUID)
