package org.misarch.catalog.persistence.model

import org.springframework.data.annotation.Id
import org.springframework.data.domain.Persistable
import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

/**
 * Join table for the many-to-many relationship between product variant versions and medias; the primary key is the
 * pair.
 *
 * @property key the product variant version and the media
 */
@Table
class ProductVariantVersionToMediaEntity(
    @Id
    val key: ProductVariantVersionToMediaKey
) : Persistable<ProductVariantVersionToMediaKey> {

    override fun getId(): ProductVariantVersionToMediaKey = key

    /** Rows are only inserted and deleted, never updated: `save` always inserts. */
    override fun isNew(): Boolean = true
}

/**
 * Primary key of [ProductVariantVersionToMediaEntity].
 *
 * @property productVariantVersionId id of the product variant version
 * @property mediaId id of the media
 */
data class ProductVariantVersionToMediaKey(val productVariantVersionId: UUID, val mediaId: UUID)
