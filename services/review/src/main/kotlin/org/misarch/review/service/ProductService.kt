package org.misarch.review.service

import org.misarch.review.event.model.ProductDTO
import org.misarch.review.persistence.model.ProductEntity
import org.misarch.review.persistence.repository.ProductRepository
import org.springframework.stereotype.Service

/**
 * Service for [ProductEntity]s
 */
@Service
class ProductService(
    private val repository: ProductRepository
) {

    /**
     * Registers a product (replica); idempotent
     *
     * @param productDTO the product to register
     */
    suspend fun registerProduct(productDTO: ProductDTO) {
        repository.createProduct(productDTO.id)
    }

}
