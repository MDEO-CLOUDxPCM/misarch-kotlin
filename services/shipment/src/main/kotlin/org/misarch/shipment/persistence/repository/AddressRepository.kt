package org.misarch.shipment.persistence.repository

import org.misarch.shipment.persistence.model.AddressEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * Repository for [AddressEntity]s
 */
@Repository
interface AddressRepository : CoroutineCrudRepository<AddressEntity, UUID> {

    /**
     * Create a new address (no `ON CONFLICT`: a duplicate id fails, like the original)
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
        """
    )
    suspend fun createAddress(
        @Param("id")
        id: UUID,
        @Param("userId")
        userId: UUID?,
        @Param("street1")
        street1: String,
        @Param("street2")
        street2: String,
        @Param("city")
        city: String,
        @Param("postalCode")
        postalCode: String,
        @Param("country")
        country: String,
        @Param("companyName")
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
