package org.misarch.shipment.persistence.repository

import org.misarch.shipment.persistence.model.AddressEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.UUID

/**
 * Repository for [AddressEntity]s
 */
interface AddressRepository : CoroutineCrudRepository<AddressEntity, UUID> {

    /**
     * Create a new address. Idempotent (fix, docs/fixes/shipment.md): a redelivered event is a no-op (the original's
     * plain INSERT failed with a duplicate key, HTTP 500, endless redelivery).
     *
     * @param id unique identifier of the address
     * @param userId unique identifier of the user, null if the address is a vendor address
     */
    @Modifying
    @Query(
        """
        INSERT INTO AddressEntity
        (id, userId, street1, street2, city, postalCode, country, companyName)
        VALUES (:id, :userId, :street1, :street2, :city, :postalCode, :country, :companyName)
        ON CONFLICT (id) DO NOTHING
        """
    )
    suspend fun createAddress(
        id: UUID,
        userId: UUID?,
        street1: String,
        street2: String,
        city: String,
        postalCode: String,
        country: String,
        companyName: String?
    ): Long

    /**
     * Find the current vendor address (the vendor address with the highest version)
     *
     * @return the current vendor address or null if there is no current vendor address
     */
    @Query("SELECT * FROM AddressEntity WHERE userId IS NULL ORDER BY version DESC LIMIT 1")
    suspend fun findCurrentVendorAddress(): AddressEntity?

}
