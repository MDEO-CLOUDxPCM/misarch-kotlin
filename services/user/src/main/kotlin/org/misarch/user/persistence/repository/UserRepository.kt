package org.misarch.user.persistence.repository

import org.misarch.user.persistence.model.UserEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Repository for [UserEntity]s
 */
interface UserRepository : CoroutineCrudRepository<UserEntity, UUID> {

    /**
     * Inserts a user with a given id (`save` would issue an UPDATE because the id is set); an existing id is left
     * unchanged (idempotent event handling).
     *
     * @return the number of inserted rows (0 if the user already exists)
     */
    @Modifying
    @Query("INSERT INTO UserEntity (id, firstName, lastName, username, dateJoined) VALUES (:id, :firstName, :lastName, :username, :dateJoined) ON CONFLICT (id) DO NOTHING")
    suspend fun createUser(
        id: UUID,
        firstName: String,
        lastName: String,
        username: String,
        dateJoined: OffsetDateTime
    ): Int

}
