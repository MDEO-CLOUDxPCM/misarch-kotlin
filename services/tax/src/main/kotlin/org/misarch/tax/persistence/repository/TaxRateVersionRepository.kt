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
     * Finds the highest version number of the versions of a tax rate (the name is the original's copy-paste name)
     *
     * @param taxRateId the id of the tax rate
     * @return the highest version, or null if the tax rate has no versions
     */
    @Query("SELECT MAX(taxRateVersionEntity.version) FROM TaxRateVersionEntity taxRateVersionEntity WHERE taxRateVersionEntity.taxRateId = :taxRateId")
    suspend fun findMaxVersionByProductVariantId(
        taxRateId: UUID
    ): Int?

}
