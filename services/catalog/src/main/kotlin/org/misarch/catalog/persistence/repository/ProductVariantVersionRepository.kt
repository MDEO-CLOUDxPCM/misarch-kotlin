package org.misarch.catalog.persistence.repository

import org.misarch.catalog.persistence.model.ProductVariantVersionEntity
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.UUID


/**
 * Repository for [ProductVariantVersionEntity]s
 */
interface ProductVariantVersionRepository : CoroutineCrudRepository<ProductVariantVersionEntity, UUID> {

    /**
     * Finds the maximum version of a product variant
     *
     * @param productVariantId the id of the product variant
     * @return the maximum version of the product variant, null if there are no versions
     */
    @Query("SELECT MAX(t.version) FROM ProductVariantVersionEntity t WHERE t.productVariantId = :productVariantId")
    suspend fun findMaxVersionByProductVariantId(productVariantId: UUID): Int?

}
