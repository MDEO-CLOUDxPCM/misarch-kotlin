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

    @Modifying
    @Query("INSERT INTO TaxRateEntity (id) VALUES (:id)")
    suspend fun createTaxRate(
        @Param("id") id: UUID
    )

}
