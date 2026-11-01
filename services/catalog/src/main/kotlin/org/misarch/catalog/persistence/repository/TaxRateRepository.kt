package org.misarch.catalog.persistence.repository

import org.misarch.catalog.persistence.model.TaxRateEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.*

/**
 * Repository for [TaxRateEntity]s
 */
interface TaxRateRepository : CoroutineCrudRepository<TaxRateEntity, UUID> {

    /** Idempotent: a redelivered creation event is a no-op. */
    @Modifying
    @Query("INSERT INTO TaxRateEntity (id) VALUES (:id) ON CONFLICT (id) DO NOTHING")
    suspend fun createTaxRate(
        id: UUID
    )

}
