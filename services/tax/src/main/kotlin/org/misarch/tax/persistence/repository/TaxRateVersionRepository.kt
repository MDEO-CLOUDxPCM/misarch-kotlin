package org.misarch.tax.persistence.repository

import org.misarch.tax.persistence.model.TaxRateVersionEntity
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.UUID

/**
 * Repository for [TaxRateVersionEntity]s
 */
interface TaxRateVersionRepository : CoroutineCrudRepository<TaxRateVersionEntity, UUID> {

    /**
     * Finds the highest version number of the versions of a tax rate
     *
     * @param taxRateId the id of the tax rate
     * @return the highest version, or null if the tax rate has no versions
     */
    @Query("SELECT MAX(t.version) FROM TaxRateVersionEntity t WHERE t.taxRateId = :taxRateId")
    suspend fun findMaxVersionByTaxRateId(taxRateId: UUID): Int?

}
