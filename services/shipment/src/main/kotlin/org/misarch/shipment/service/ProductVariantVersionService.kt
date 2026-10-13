package org.misarch.shipment.service

import org.misarch.shipment.event.model.ProductVariantVersionDTO
import org.misarch.shipment.persistence.model.ProductVariantVersionEntity
import org.misarch.shipment.persistence.repository.ProductVariantVersionRepository
import org.springframework.stereotype.Service

/**
 * Service for [ProductVariantVersionEntity]s
 *
 * @param repository the repository for [ProductVariantVersionEntity]s
 */
@Service
class ProductVariantVersionService(
    private val repository: ProductVariantVersionRepository
) {

    /**
     * Registers a product variant version
     *
     * @param productVariantVersionDTO the product variant version to register
     */
    suspend fun registerProductVariantVersion(productVariantVersionDTO: ProductVariantVersionDTO) {
        repository.createProductVariantVersion(productVariantVersionDTO.id, productVariantVersionDTO.weight)
    }

}
