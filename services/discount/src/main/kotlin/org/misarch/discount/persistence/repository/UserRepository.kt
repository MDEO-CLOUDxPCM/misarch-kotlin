package org.misarch.discount.persistence.repository

import org.misarch.discount.persistence.model.UserEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.*

/**
 * Repository for [UserEntity]s
 */
interface UserRepository : CoroutineCrudRepository<UserEntity, UUID> {

    /**
     * Creates a user (replica, fed by an event); a redelivered event is a no-op.
     *
     * @param id the id of the user
     */
    @Modifying
    @Query("INSERT INTO UserEntity (id) VALUES (:id) ON CONFLICT (id) DO NOTHING")
    suspend fun createUser(
        id: UUID
    )

}
