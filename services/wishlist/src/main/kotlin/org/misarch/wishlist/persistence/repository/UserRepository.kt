package org.misarch.wishlist.persistence.repository

import org.misarch.wishlist.persistence.model.UserEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.UUID

/**
 * Repository for [UserEntity]s
 */
interface UserRepository : CoroutineCrudRepository<UserEntity, UUID> {

    /** Idempotent: a redelivered `user/user/created` is a no-op (docs/fixes/wishlist.md). */
    @Modifying
    @Query("INSERT INTO UserEntity (id) VALUES (:id) ON CONFLICT (id) DO NOTHING")
    suspend fun createUser(id: UUID)

}
