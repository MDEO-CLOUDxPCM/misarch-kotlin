package org.misarch.order.persistence.repository

import org.misarch.order.persistence.model.TaxRateEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.UUID

/**
 * Repository for [TaxRateEntity]s
 */
interface TaxRateRepository : CoroutineCrudRepository<TaxRateEntity, UUID> {

    /**
     * Sets the current version of a tax rate (creating the tax rate if it is unknown)
     *
     * @param id the id of the tax rate
     * @param versionId the id of the version
     * @param rate the rate of the version
     * @param version the version number
     */
    @Modifying
    @Query(
        """
        INSERT INTO TaxRateEntity (id, currentVersionId, currentVersionRate, currentVersionVersion)
        VALUES (:id, :versionId, :rate, :version) ON CONFLICT (id) DO UPDATE SET
        currentVersionId = EXCLUDED.currentVersionId, currentVersionRate = EXCLUDED.currentVersionRate,
        currentVersionVersion = EXCLUDED.currentVersionVersion
        """
    )
    suspend fun upsertCurrentVersion(id: UUID, versionId: UUID, rate: Double, version: Long)

}
