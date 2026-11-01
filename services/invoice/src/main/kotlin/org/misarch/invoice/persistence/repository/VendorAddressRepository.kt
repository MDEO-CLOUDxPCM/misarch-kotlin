package org.misarch.invoice.persistence.repository

import org.misarch.invoice.persistence.model.VendorAddressEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.UUID

/**
 * Repository for [VendorAddressEntity]s
 */
interface VendorAddressRepository : CoroutineCrudRepository<VendorAddressEntity, UUID> {

    /**
     * Creates or updates a vendor address (replica)
     */
    @Modifying
    @Query(
        "INSERT INTO VendorAddressEntity (id, street1, street2, city, postalCode, country, companyName) " +
            "VALUES (:id, :street1, :street2, :city, :postalCode, :country, :companyName) ON CONFLICT (id) DO UPDATE SET " +
            "street1 = EXCLUDED.street1, street2 = EXCLUDED.street2, city = EXCLUDED.city, " +
            "postalCode = EXCLUDED.postalCode, country = EXCLUDED.country, companyName = EXCLUDED.companyName"
    )
    suspend fun upsertVendorAddress(
        id: UUID, street1: String, street2: String, city: String, postalCode: String, country: String, companyName: String?
    )

    /**
     * Finds the first complete vendor address (in the order of arrival)
     */
    suspend fun findFirstByStreet1IsNotNullOrderBySeq(): VendorAddressEntity?

}
