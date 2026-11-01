package org.misarch.discount.persistence.repository

import org.misarch.discount.persistence.model.ProductVariantEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.*

/**
 * Repository for [ProductVariantEntity]s
 */
interface ProductVariantRepository : CoroutineCrudRepository<ProductVariantEntity, UUID> {

    /**
     * Creates a productVariant
     *
     * @param id the id of the productVariant
     * @param productId the id of the product
     */
    @Modifying
    @Query("INSERT INTO ProductVariantEntity (id, productId) VALUES (:id, :productId) ON CONFLICT (id) DO NOTHING")
    suspend fun createProductVariant(
        id: UUID,
        productId: UUID,
    )

}
