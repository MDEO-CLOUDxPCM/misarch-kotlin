package org.misarch.address.persistence.repository

import org.misarch.address.persistence.model.AddressEntity
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.UUID

/**
 * Repository for [AddressEntity]s
 */
interface AddressRepository : CoroutineCrudRepository<AddressEntity, UUID> {

    /**
     * Find the current vendor address
     *
     * @return the current vendor address or null if there is no current vendor address
     */
    @Query("SELECT * FROM AddressEntity WHERE userId IS NULL ORDER BY version DESC LIMIT 1")
    suspend fun findCurrentVendorAddress(): AddressEntity?
}
