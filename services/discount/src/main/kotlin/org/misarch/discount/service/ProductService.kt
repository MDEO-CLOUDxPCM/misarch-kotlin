package org.misarch.discount.service

import org.misarch.discount.event.model.ProductDTO
import org.misarch.discount.persistence.model.ProductEntity
import org.misarch.discount.persistence.repository.ProductRepository
import org.misarch.discount.persistence.repository.ProductToCategoryRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Service for [ProductEntity]s
 */
@Service
class ProductService(
    private val repository: ProductRepository, private val productToCategoryRepository: ProductToCategoryRepository
) {

    /**
     * Registers a product
     * One transaction with idempotent inserts, so that a redelivered event (or a retry after a partial failure)
     * succeeds.
     *
     * @param productDTO the product to register
     */
    @Transactional
    suspend fun registerProduct(productDTO: ProductDTO) {
        repository.createProduct(productDTO.id)
        productDTO.categoryIds.forEach { productToCategoryRepository.createProductToCategory(productDTO.id, it) }
    }

}
