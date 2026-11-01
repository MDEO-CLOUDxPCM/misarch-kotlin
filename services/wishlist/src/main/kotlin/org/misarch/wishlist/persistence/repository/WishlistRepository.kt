package org.misarch.wishlist.persistence.repository

import org.misarch.wishlist.persistence.model.WishlistEntity
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.UUID

/**
 * Repository for [WishlistEntity]s
 */
interface WishlistRepository : CoroutineCrudRepository<WishlistEntity, UUID> {

    /**
     * The wishlist [id], locked for the current transaction (concurrent changes of one wishlist run one after the
     * other), or null
     */
    @Query("SELECT * FROM WishlistEntity WHERE id = :id FOR UPDATE")
    suspend fun findByIdForUpdate(id: UUID): WishlistEntity?

}
