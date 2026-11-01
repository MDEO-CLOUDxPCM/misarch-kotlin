package org.misarch.discount.persistence.repository

import org.misarch.discount.persistence.model.ProductToCategoryEntity
import org.misarch.discount.persistence.model.ProductToCategoryKey
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.*

/**
 * Repository for [ProductToCategoryEntity]s
 */
interface ProductToCategoryRepository : CoroutineCrudRepository<ProductToCategoryEntity, ProductToCategoryKey> {

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
    suspend fun createProductToCategory(productId: UUID, categoryId: UUID)
}
