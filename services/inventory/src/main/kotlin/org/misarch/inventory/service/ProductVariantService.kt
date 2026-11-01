package org.misarch.inventory.service

import org.misarch.inventory.event.model.ProductVariantDTO
import org.misarch.inventory.persistence.repository.ProductVariantRepository
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Service for product variants (replicated from the catalog)
 *
 * @property repository the product variant repository
 */
@Service
class ProductVariantService(
    private val repository: ProductVariantRepository
) {

    /**
     * Registers a product variant created in the catalog (idempotent)
     *
     * @param productVariantDTO the created product variant
     */
    suspend fun registerProductVariant(productVariantDTO: ProductVariantDTO) {
        repository.createProductVariant(productVariantDTO.id)
    }

    /**
     * Checks that a product variant is known
     *
     * @param id the id of the product variant
     * @throws NoSuchElementException if the product variant is unknown
     */
    suspend fun requireProductVariant(id: UUID) {
        if (!repository.existsById(id)) {
            throw NoSuchElementException()
        }
    }

}
