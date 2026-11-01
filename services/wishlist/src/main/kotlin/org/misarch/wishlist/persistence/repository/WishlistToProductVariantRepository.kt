package org.misarch.wishlist.persistence.repository

import org.misarch.wishlist.persistence.model.WishlistToProductVariantEntity
import org.misarch.wishlist.persistence.model.WishlistToProductVariantKey
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.UUID

/**
 * Repository for [WishlistToProductVariantEntity]s
 */
interface WishlistToProductVariantRepository :
    CoroutineCrudRepository<WishlistToProductVariantEntity, WishlistToProductVariantKey> {

    /**
     * The product variant links of a wishlist
     */
    suspend fun findByKeyWishlistId(wishlistId: UUID): List<WishlistToProductVariantEntity>

    /**
     * Links a wishlist to the product variants [productVariantIds] (distinct) in one statement
     */
    @Modifying
    @Query(
        "INSERT INTO WishlistToProductVariantEntity (wishlistId, productVariantId) " +
            "SELECT :wishlistId, unnest(:productVariantIds)"
    )
    suspend fun createAll(wishlistId: UUID, productVariantIds: Array<UUID>)

    /**
     * Removes all product variant links of a wishlist
     */
    @Modifying
    @Query("DELETE FROM WishlistToProductVariantEntity WHERE wishlistId = :wishlistId")
    suspend fun deleteByWishlistId(wishlistId: UUID): Long

}
