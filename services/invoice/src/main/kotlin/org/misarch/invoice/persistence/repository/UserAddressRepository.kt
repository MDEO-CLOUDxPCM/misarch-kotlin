package org.misarch.invoice.persistence.repository

import org.misarch.invoice.persistence.model.UserAddressEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.UUID

/**
 * Repository for [UserAddressEntity]s
 */
interface UserAddressRepository : CoroutineCrudRepository<UserAddressEntity, UUID> {

    /**
     * Creates a user address (replica); an existing one is kept
     */
    @Modifying
    @Query(
        """
        INSERT INTO UserAddressEntity (id, userId, street1, street2, city, postalCode, country, companyName, firstName, lastName)
        VALUES (:id, :userId, :street1, :street2, :city, :postalCode, :country, :companyName, :firstName, :lastName)
        ON CONFLICT (id) DO NOTHING
        """
    )
    suspend fun createUserAddress(
        id: UUID, userId: UUID, street1: String, street2: String, city: String, postalCode: String, country: String,
        companyName: String?, firstName: String?, lastName: String?
    )

    /**
     * Marks a user address as archived
     *
     * @param id the id of the address
     * @param userId the id of the user
     */
    @Modifying
    @Query("UPDATE UserAddressEntity SET isArchived = TRUE WHERE id = :id AND userId = :userId")
    suspend fun archiveUserAddress(id: UUID, userId: UUID)

}
