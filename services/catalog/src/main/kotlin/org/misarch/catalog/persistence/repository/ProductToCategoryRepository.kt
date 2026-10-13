package org.misarch.catalog.persistence.repository

import org.misarch.catalog.persistence.model.ProductToCategoryEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import java.util.*

/**
 * Repository for [ProductToCategoryEntity]s
 */
@Repository
interface ProductToCategoryRepository : CoroutineCrudRepository<ProductToCategoryEntity, UUID> {

    /**
     * Finds all product to category relations for a given product
     *
     * @param productId the id of the product
     * @return list of product to category relations
     */
    suspend fun findByProductId(productId: UUID): List<ProductToCategoryEntity>

}
