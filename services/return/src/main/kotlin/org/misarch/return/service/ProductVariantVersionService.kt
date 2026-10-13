package org.misarch.`return`.service

import org.misarch.`return`.event.model.ProductVariantVersionDTO
import org.misarch.`return`.persistence.model.ProductVariantVersionEntity
import org.misarch.`return`.persistence.repository.ProductVariantVersionRepository
import org.springframework.stereotype.Service

/**
 * Service for [ProductVariantVersionEntity]s
 *
 * @param repository the provided repository
 */
@Service
class ProductVariantVersionService(
    private val repository: ProductVariantVersionRepository
) {

    /**
     * Registers a product variant version (never called: the topic is not subscribed, like in the original)
     *
     * @param productVariantVersionDTO the product variant version to register
     */
    suspend fun registerProductVariantVersion(productVariantVersionDTO: ProductVariantVersionDTO) {
        repository.createProductVariantVersion(
            productVariantVersionDTO.id,
            productVariantVersionDTO.canBeReturnedForDays
        )
    }

}
