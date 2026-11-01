package org.misarch.notification.persistence.repository

import org.misarch.notification.persistence.model.UserEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.UUID

/**
 * Repository for [UserEntity]s
 */
interface UserRepository : CoroutineCrudRepository<UserEntity, UUID> {

    /** Idempotent: a redelivered `user/user/created` is a no-op (docs/fixes/notification.md). */
    @Modifying
    @Query("INSERT INTO UserEntity (id) VALUES (:id) ON CONFLICT (id) DO NOTHING")
    suspend fun createUser(
        id: UUID
    )

}
