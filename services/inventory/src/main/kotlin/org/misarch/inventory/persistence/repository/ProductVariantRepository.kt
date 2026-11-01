package org.misarch.inventory.persistence.repository

import org.misarch.inventory.persistence.model.ProductVariantEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.UUID

/**
 * Repository for [ProductVariantEntity]s
 */
interface ProductVariantRepository : CoroutineCrudRepository<ProductVariantEntity, UUID> {

    /**
     * Inserts a product variant; an existing one is kept (redelivered events)
     *
     * @param id the id of the product variant
     */
    @Modifying
    @Query("INSERT INTO ProductVariantEntity (id) VALUES (:id) ON CONFLICT (id) DO NOTHING")
    suspend fun createProductVariant(id: UUID): Int

}
