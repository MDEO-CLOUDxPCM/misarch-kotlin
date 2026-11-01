package org.misarch.discount.persistence.model

import org.springframework.data.annotation.Id
import org.springframework.data.domain.Persistable
import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

/**
 * Join table for the many-to-many relationship between discounts and categories; the primary key is the pair.
 *
 * @property key the discount and the category
 */
@Table
class DiscountToCategoryEntity(
    @Id
    val key: DiscountToCategoryKey
) : Persistable<DiscountToCategoryKey> {

    override fun getId(): DiscountToCategoryKey = key

    /** Rows are only inserted and deleted, never updated: `save` always inserts. */
    override fun isNew(): Boolean = true
}

/**
 * Primary key of [DiscountToCategoryEntity].
 *
 * @property discountId id of the discount
 * @property categoryId id of the category
 */
data class DiscountToCategoryKey(val discountId: UUID, val categoryId: UUID)
