package org.misarch.shoppingcart.persistence.repository

import org.misarch.shoppingcart.persistence.model.UserEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Repository for [UserEntity]s
 */
interface UserRepository : CoroutineCrudRepository<UserEntity, UUID> {

    /** Idempotent: a redelivered `user/user/created` is a no-op. */
    @Modifying
    @Query(
        "INSERT INTO UserEntity (id, shoppingcartLastUpdatedAt) VALUES (:id, :shoppingcartLastUpdatedAt) " +
            "ON CONFLICT (id) DO NOTHING"
    )
    suspend fun createUser(id: UUID, shoppingcartLastUpdatedAt: OffsetDateTime)

    /**
     * Locks the user row (and with it the user's shopping cart) for the current transaction
     *
     * @param id the id of the user
     * @return the id, null if the user does not exist
     */
    @Query("SELECT id FROM UserEntity WHERE id = :id FOR UPDATE")
    suspend fun lockById(id: UUID): UUID?

    /**
     * Sets when the user's shopping cart was last changed (also locks the user row)
     *
     * @return the number of updated rows
     */
    @Modifying
    @Query("UPDATE UserEntity SET shoppingcartLastUpdatedAt = :shoppingcartLastUpdatedAt WHERE id = :id")
    suspend fun updateShoppingcartLastUpdatedAt(id: UUID, shoppingcartLastUpdatedAt: OffsetDateTime): Long

}
