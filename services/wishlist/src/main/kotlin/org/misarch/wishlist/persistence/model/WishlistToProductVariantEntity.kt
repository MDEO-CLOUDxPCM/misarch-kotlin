package org.misarch.wishlist.persistence.model

import org.springframework.data.annotation.Id
import org.springframework.data.domain.Persistable
import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

/**
 * Link between a wishlist and one of its product variants (primary key: the pair)
 *
 * @property key the wishlist and the product variant
 */
@Table
class WishlistToProductVariantEntity(
    @Id
    val key: WishlistToProductVariantKey
) : Persistable<WishlistToProductVariantKey> {

    override fun getId() = key

    /** link rows are only inserted and deleted: `save()` always inserts */
    override fun isNew() = true

}

/**
 * Key of a [WishlistToProductVariantEntity]
 *
 * @property wishlistId id of the wishlist
 * @property productVariantId id of the product variant
 */
data class WishlistToProductVariantKey(
    val wishlistId: UUID,
    val productVariantId: UUID
)
