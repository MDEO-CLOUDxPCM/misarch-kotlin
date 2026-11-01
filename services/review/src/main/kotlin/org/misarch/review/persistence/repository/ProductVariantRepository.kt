package org.misarch.review.persistence.repository

import org.misarch.review.persistence.model.ProductVariantEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.UUID

/**
 * Repository for [ProductVariantEntity]s
 */
interface ProductVariantRepository : CoroutineCrudRepository<ProductVariantEntity, UUID> {

    /** Idempotent: a redelivered `catalog/product-variant/created` is a no-op. */
    @Modifying
    @Query("INSERT INTO ProductVariantEntity (id, productId) VALUES (:id, :productId) ON CONFLICT (id) DO NOTHING")
    suspend fun createProductVariant(id: UUID, productId: UUID)

}
