package org.misarch.discount.service

import org.misarch.discount.event.model.ProductVariantDTO
import org.misarch.discount.persistence.model.ProductVariantEntity
import org.misarch.discount.persistence.repository.ProductVariantRepository
import org.springframework.stereotype.Service

/**
 * Service for [ProductVariantEntity]s
 *
 * @param repository the repository for [ProductVariantEntity]s
 */
@Service
class ProductVariantService(
    repository: ProductVariantRepository
) : BaseService<ProductVariantEntity, ProductVariantRepository>(repository) {

    /**
     * Registers a productVariant
     *
     * @param productVariantDTO the productVariant to register
     */
    suspend fun registerProductVariant(productVariantDTO: ProductVariantDTO) {
        repository.createProductVariant(productVariantDTO.id, productVariantDTO.productId)
    }

}
