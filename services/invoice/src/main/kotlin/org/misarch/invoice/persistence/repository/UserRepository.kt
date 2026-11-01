package org.misarch.invoice.persistence.repository

import org.misarch.invoice.persistence.model.UserEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.UUID

/**
 * Repository for [UserEntity]s
 */
interface UserRepository : CoroutineCrudRepository<UserEntity, UUID> {

    /**
     * Creates a user (replica); an existing user is kept
     */
    @Modifying
    @Query(
        """
        INSERT INTO UserEntity (id, firstName, lastName)
        VALUES (:id, :firstName, :lastName)
        ON CONFLICT (id) DO NOTHING
        """
    )
    suspend fun createUser(id: UUID, firstName: String, lastName: String)

}
