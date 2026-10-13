package org.misarch.catalog.persistence.repository

import org.misarch.catalog.persistence.model.ProductVariantEntity
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.*

/**
 * Repository for [ProductVariantEntity]s
 */
@Repository
interface ProductVariantRepository : CoroutineCrudRepository<ProductVariantEntity, UUID> {

    /**
     * Locks the product variant row until the end of the transaction (serializes version numbering).
     *
     * @param id the id of the product variant
     * @return the id, null if the product variant does not exist
     */
    @Query("SELECT id FROM ProductVariantEntity WHERE id = :id FOR UPDATE")
    suspend fun lockById(@Param("id") id: UUID): UUID?
}
