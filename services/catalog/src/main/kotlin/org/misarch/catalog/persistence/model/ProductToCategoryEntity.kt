package org.misarch.catalog.persistence.model

import org.springframework.data.annotation.Id
import org.springframework.data.domain.Persistable
import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

/**
 * Join table for the many-to-many relationship between products and categories; the primary key is the pair.
 *
 * @property key the product and the category
 */
@Table
class ProductToCategoryEntity(
    @Id
    val key: ProductToCategoryKey
) : Persistable<ProductToCategoryKey> {

    override fun getId(): ProductToCategoryKey = key

    /** Rows are only inserted and deleted, never updated: `save` always inserts. */
    override fun isNew(): Boolean = true
}

/**
 * Primary key of [ProductToCategoryEntity].
 *
 * @property productId id of the product
 * @property categoryId id of the category
 */
data class ProductToCategoryKey(val productId: UUID, val categoryId: UUID)
