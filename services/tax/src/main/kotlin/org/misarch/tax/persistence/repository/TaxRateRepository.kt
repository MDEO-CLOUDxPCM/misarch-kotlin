package org.misarch.tax.persistence.repository

import org.misarch.tax.persistence.model.TaxRateEntity
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.UUID

/**
 * Repository for [TaxRateEntity]s
 */
interface TaxRateRepository : CoroutineCrudRepository<TaxRateEntity, UUID> {

    /**
     * Locks the row of a tax rate until the end of the current transaction (`SELECT ... FOR UPDATE`), so that
     * concurrent `createTaxRateVersion` calls for the same tax rate compute `MAX(version) + 1` one after another
     * (fix of the duplicate version numbers, docs/fixes/tax.md).
     *
     * @param id the id of the tax rate
     * @return the id, or null if the tax rate does not exist
     */
    @Query("SELECT id FROM TaxRateEntity WHERE id = :id FOR UPDATE")
    suspend fun lockById(
        id: UUID
    ): UUID?

}
