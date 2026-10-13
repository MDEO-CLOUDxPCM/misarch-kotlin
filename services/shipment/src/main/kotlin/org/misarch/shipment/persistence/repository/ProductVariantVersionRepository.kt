package org.misarch.shipment.persistence.repository

import org.misarch.shipment.persistence.model.ProductVariantVersionEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * Repository for [ProductVariantVersionEntity]s
 */
@Repository
interface ProductVariantVersionRepository : CoroutineCrudRepository<ProductVariantVersionEntity, UUID> {

    /**
     * Create a new product variant version. Idempotent (fix, docs/fixes/shipment.md): a redelivered event is a no-op.
     *
     * @param id unique identifier of the product variant version
     * @param weight weight of the product variant version in kg
     */
    @Modifying
    @Query("INSERT INTO ProductVariantVersionEntity (id, weight) VALUES (:id, :weight) ON CONFLICT (id) DO NOTHING")
    suspend fun createProductVariantVersion(
        @Param("id")
        id: UUID,
        @Param("weight")
        weight: Double
    ): Long

}
