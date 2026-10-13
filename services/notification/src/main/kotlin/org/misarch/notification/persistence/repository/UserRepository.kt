package org.misarch.notification.persistence.repository

import org.misarch.notification.persistence.model.UserEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * Repository for [UserEntity]s
 */
@Repository
interface UserRepository : CoroutineCrudRepository<UserEntity, UUID> {

    @Modifying
    @Query("INSERT INTO UserEntity (id) VALUES (:id)")
    suspend fun createUser(
        @Param("id") id: UUID
    )

}
