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
     * Create a new product variant version (no `ON CONFLICT`: a duplicate id fails, like the original)
     *
     * @param id unique identifier of the product variant version
     * @param weight weight of the product variant version in kg
     */
    @Modifying
    @Query("INSERT INTO ProductVariantVersionEntity (id, weight) VALUES (:id, :weight)")
    suspend fun createProductVariantVersion(
        @Param("id")
        id: UUID,
        @Param("weight")
        weight: Double
    ): Long

}
