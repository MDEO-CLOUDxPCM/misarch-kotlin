package org.misarch.catalog.persistence.repository

import org.misarch.catalog.persistence.model.TaxRateEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.*

/**
 * Repository for [TaxRateEntity]s
 */
@Repository
interface TaxRateRepository : CoroutineCrudRepository<TaxRateEntity, UUID> {

    /** Idempotent: a redelivered creation event is a no-op (docs/fixes/catalog.md). */
    @Modifying
    @Query("INSERT INTO TaxRateEntity (id) VALUES (:id) ON CONFLICT (id) DO NOTHING")
    suspend fun createTaxRate(
        @Param("id") id: UUID
    )

}
