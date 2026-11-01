package org.misarch.shoppingcart.persistence.repository

import org.misarch.shoppingcart.persistence.model.ProductVariantEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.UUID

/**
 * Repository for [ProductVariantEntity]s
 */
interface ProductVariantRepository : CoroutineCrudRepository<ProductVariantEntity, UUID> {

    /** Idempotent: a redelivered `catalog/product-variant/created` is a no-op (docs/fixes/shoppingcart.md). */
    @Modifying
    @Query("INSERT INTO ProductVariantEntity (id) VALUES (:id) ON CONFLICT (id) DO NOTHING")
    suspend fun createProductVariant(id: UUID)

}
