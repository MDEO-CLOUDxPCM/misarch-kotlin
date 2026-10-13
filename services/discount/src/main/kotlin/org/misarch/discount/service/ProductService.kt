package org.misarch.discount.service

import org.misarch.discount.event.model.ProductDTO
import org.misarch.discount.persistence.model.ProductEntity
import org.misarch.discount.persistence.repository.ProductRepository
import org.misarch.discount.persistence.repository.ProductToCategoryRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Service for [ProductEntity]s
 *
 * @param repository the repository for [ProductEntity]s
 * @param productToCategoryRepository the repository for [ProductToCategoryRepository]s
 */
@Service
class ProductService(
    repository: ProductRepository, private val productToCategoryRepository: ProductToCategoryRepository
) : BaseService<ProductEntity, ProductRepository>(repository) {

    /**
     * Registers a product
     * FIXED (docs/fixes/discount.md, Q6): one transaction, and idempotent inserts, so that a redelivered event (or a
     * retry after a partial failure) succeeds instead of failing with a duplicate key forever.
     *
     * @param productDTO the product to register
     */
    @Transactional
    suspend fun registerProduct(productDTO: ProductDTO) {
        repository.createProduct(productDTO.id)
        productDTO.categoryIds.forEach { productToCategoryRepository.createProductToCategory(productDTO.id, it) }
    }

}
