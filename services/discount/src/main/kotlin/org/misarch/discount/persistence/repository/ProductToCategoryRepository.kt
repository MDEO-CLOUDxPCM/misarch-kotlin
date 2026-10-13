package org.misarch.discount.persistence.repository

import org.misarch.discount.persistence.model.ProductToCategoryEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.*

/**
 * Repository for [ProductToCategoryEntity]s
 */
@Repository
interface ProductToCategoryRepository : CoroutineCrudRepository<ProductToCategoryEntity, UUID> {

    /**
     * Links a product to a category; an existing link is kept (idempotent, docs/fixes/discount.md Q6)
     *
     * @param productId id of the product
     * @param categoryId id of the category
     */
    @Modifying
    @Query(
        "INSERT INTO ProductToCategoryEntity (productId, categoryId) VALUES (:productId, :categoryId) " +
            "ON CONFLICT (productId, categoryId) DO NOTHING"
    )
    suspend fun createProductToCategory(@Param("productId") productId: UUID, @Param("categoryId") categoryId: UUID)
}
