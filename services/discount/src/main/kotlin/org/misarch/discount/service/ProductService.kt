package org.misarch.discount.service

import kotlinx.coroutines.flow.toList
import org.misarch.discount.event.model.ProductDTO
import org.misarch.discount.persistence.model.ProductEntity
import org.misarch.discount.persistence.model.ProductToCategoryEntity
import org.misarch.discount.persistence.repository.ProductRepository
import org.misarch.discount.persistence.repository.ProductToCategoryRepository
import org.springframework.stereotype.Service

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
     * Not transactional (like the original): if a category link fails, the product row stays.
     *
     * @param productDTO the product to register
     */
    suspend fun registerProduct(productDTO: ProductDTO) {
        repository.createProduct(productDTO.id)
        productToCategoryRepository.saveAll(productDTO.categoryIds.map {
            ProductToCategoryEntity(
                productId = productDTO.id, categoryId = it, id = null
            )
        }).toList()
    }

}
