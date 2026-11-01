package org.misarch.shoppingcart.persistence.repository

import org.misarch.shoppingcart.persistence.model.ShoppingCartItemEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.UUID

/**
 * Repository for [ShoppingCartItemEntity]s
 */
interface ShoppingCartItemRepository : CoroutineCrudRepository<ShoppingCartItemEntity, UUID> {

    /**
     * The first added item of a user's shopping cart for a product variant
     */
    suspend fun findFirstByUserIdAndProductVariantIdOrderByAddedAtAscIdAsc(
        userId: UUID, productVariantId: UUID
    ): ShoppingCartItemEntity?

    /**
     * Removes all items of a user's shopping cart
     */
    @Modifying
    @Query("DELETE FROM ShoppingCartItemEntity WHERE userId = :userId")
    suspend fun deleteByUserId(userId: UUID): Long

    /**
     * Removes the items [ids] from a user's shopping cart (ids of other carts are ignored)
     *
     * @return the number of removed items
     */
    @Modifying
    @Query("DELETE FROM ShoppingCartItemEntity WHERE userId = :userId AND id = ANY(:ids)")
    suspend fun deleteByUserIdAndIdIn(userId: UUID, ids: Array<UUID>): Long

}
