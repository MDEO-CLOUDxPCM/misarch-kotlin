package org.misarch.order.service

import org.misarch.order.event.model.ProductVariantDTO
import org.misarch.order.event.model.ProductVariantVersionDTO
import org.misarch.order.persistence.model.ProductVariantEntity
import org.misarch.order.persistence.repository.ProductVariantRepository
import org.springframework.stereotype.Service

/**
 * Service for [ProductVariantEntity]s
 */
@Service
class ProductVariantService(
    private val repository: ProductVariantRepository
) {

    /**
     * Registers a new product variant version as the current version of its product variant (an unknown product
     * variant is registered as publicly visible)
     *
     * @param productVariantVersionDTO the created product variant version
     */
    suspend fun registerProductVariantVersion(productVariantVersionDTO: ProductVariantVersionDTO) {
        repository.upsertCurrentVersion(
            productVariantVersionDTO.productVariantId,
            productVariantVersionDTO.id,
            productVariantVersionDTO.retailPrice,
            productVariantVersionDTO.taxRateId
        )
    }

    /**
     * Updates the visibility of a known product variant
     *
     * @param productVariantDTO the updated product variant
     */
    suspend fun updateProductVariant(productVariantDTO: ProductVariantDTO) {
        repository.updateVisibility(productVariantDTO.id, productVariantDTO.isPubliclyVisible)
    }

}
