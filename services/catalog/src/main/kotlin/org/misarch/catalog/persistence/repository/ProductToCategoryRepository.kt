package org.misarch.catalog.persistence.repository

import org.misarch.catalog.persistence.model.ProductToCategoryEntity
import org.misarch.catalog.persistence.model.ProductToCategoryKey
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.UUID

/**
 * Repository for [ProductToCategoryEntity]s
 */
interface ProductToCategoryRepository : CoroutineCrudRepository<ProductToCategoryEntity, ProductToCategoryKey> {

    /**
     * Finds all product to category relations for a given product
     *
     * @param productId the id of the product
     * @return list of product to category relations
     */
    suspend fun findByKeyProductId(productId: UUID): List<ProductToCategoryEntity>

}
