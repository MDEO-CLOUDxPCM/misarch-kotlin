package org.misarch.catalog.service

import org.misarch.catalog.event.CatalogEvents
import org.misarch.catalog.event.model.UpdatedProductVariantDTO
import org.misarch.catalog.graphql.input.CreateProductVariantInput
import org.misarch.catalog.graphql.input.ProductVariantInput
import org.misarch.catalog.graphql.input.UpdateProductVariantInput
import org.misarch.catalog.persistence.model.ProductEntity
import org.misarch.catalog.persistence.model.ProductVariantEntity
import org.misarch.catalog.persistence.model.ProductVariantVersionEntity
import org.misarch.catalog.persistence.repository.ProductRepository
import org.misarch.catalog.persistence.repository.ProductVariantRepository
import org.misarch.common.event.EventPublisher
import org.misarch.common.persistence.findByIdOrThrow
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Service for [ProductVariantEntity]s
 */
@Service
class ProductVariantService(
    private val repository: ProductVariantRepository,
    private val productVariantVersionService: ProductVariantVersionService,
    private val productRepository: ProductRepository,
    private val eventPublisher: EventPublisher
) {

    /**
     * Creates a product variant, also creates the initial version
     * Also checks if the product exists
     *
     * @param input defines the product variant (and initial version) to be created
     * @return the created product variant
     */
    @Transactional
    suspend fun createProductVariant(input: CreateProductVariantInput): ProductVariantEntity {
        require(productRepository.existsById(input.productId)) { "Product with id ${input.productId} does not exist." }
        val (productVariant, initialVersion) = addProductVariant(input, input.productId)
        eventPublisher.publishEvent(CatalogEvents.PRODUCT_VARIANT_CREATED, productVariant.toEventDTO())
        eventPublisher.publishEvent(
            CatalogEvents.PRODUCT_VARIANT_VERSION_CREATED,
            initialVersion.toEventDTO(input.initialVersion.mediaIds.toSet())
        )
        return productVariant
    }

    /**
     * Creates a product variant without checking if the product exists
     *
     * @param input defines the product variant (and initial version) to be created
     * @param productId the id of the product
     * @return the created product variant and the initial version
     */
    suspend fun addProductVariant(
        input: ProductVariantInput,
        productId: UUID
    ): Pair<ProductVariantEntity, ProductVariantVersionEntity> {
        val productVariant = ProductVariantEntity(
            isPubliclyVisible = input.isPubliclyVisible,
            productId = productId,
            currentVersion = null,
            id = null
        )
        val savedProductVariant = repository.save(productVariant)
        val initialVersion = productVariantVersionService.addProductVariantVersion(
            input.initialVersion,
            savedProductVariant.id!!
        )
        savedProductVariant.currentVersion = initialVersion.id!!
        val newSavedProductVariant = repository.save(savedProductVariant)
        return Pair(newSavedProductVariant, initialVersion)
    }

    /**
     * Updates a product variant
     *
     * @param input defines the product to be updated
     * @return the updated product variant
     */
    @Transactional
    suspend fun updateProductVariant(input: UpdateProductVariantInput): ProductVariantEntity {
        val productVariant = repository.findByIdOrThrow(input.id)
        if (input.isPubliclyVisible != null) {
            productVariant.isPubliclyVisible = input.isPubliclyVisible
        }
        val savedProductVariant = repository.save(productVariant)
        eventPublisher.publishEvent(
            CatalogEvents.PRODUCT_VARIANT_UPDATED,
            UpdatedProductVariantDTO(savedProductVariant.id!!, savedProductVariant.isPubliclyVisible)
        )
        return savedProductVariant
    }

}
