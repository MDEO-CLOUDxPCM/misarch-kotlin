package org.misarch.order.persistence.repository

import org.misarch.order.persistence.model.UserAddressEntity
import org.misarch.order.persistence.model.UserAddressKey
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.UUID

/**
 * Repository for [UserAddressEntity]s
 */
interface UserAddressRepository : CoroutineCrudRepository<UserAddressEntity, UserAddressKey> {

    /**
     * Creates a user address (replica); an existing one is kept
     *
     * @param userId the id of the user
     * @param id the id of the address
     */
    @Modifying
    @Query("INSERT INTO UserAddressEntity (userId, id) VALUES (:userId, :id) ON CONFLICT DO NOTHING")
    suspend fun createUserAddress(userId: UUID, id: UUID)

}
