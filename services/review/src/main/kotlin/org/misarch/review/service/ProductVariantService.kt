package org.misarch.review.service

import org.misarch.review.event.model.ProductVariantDTO
import org.misarch.review.persistence.model.ProductVariantEntity
import org.misarch.review.persistence.repository.ProductVariantRepository
import org.springframework.stereotype.Service

/**
 * Service for [ProductVariantEntity]s
 */
@Service
class ProductVariantService(
    private val repository: ProductVariantRepository
) {

    /**
     * Registers a product variant with its product (replica); idempotent
     *
     * @param productVariantDTO the product variant to register
     */
    suspend fun registerProductVariant(productVariantDTO: ProductVariantDTO) {
        repository.createProductVariant(productVariantDTO.id, productVariantDTO.productId)
    }

}
