package org.misarch.review.persistence.repository

import org.misarch.review.persistence.model.ProductEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.UUID

/**
 * Repository for [ProductEntity]s
 */
interface ProductRepository : CoroutineCrudRepository<ProductEntity, UUID> {

    /** Idempotent: a redelivered creation event is a no-op (docs/fixes/review.md). */
    @Modifying
    @Query("INSERT INTO ProductEntity (id) VALUES (:id) ON CONFLICT (id) DO NOTHING")
    suspend fun createProduct(id: UUID)

}
