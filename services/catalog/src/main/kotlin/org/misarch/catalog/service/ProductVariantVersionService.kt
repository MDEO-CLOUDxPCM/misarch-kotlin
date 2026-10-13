package org.misarch.catalog.service

import kotlinx.coroutines.flow.toList
import org.misarch.catalog.event.CatalogEvents
import org.misarch.catalog.graphql.input.CreateProductVariantVersionInput
import org.misarch.catalog.graphql.input.ProductVariantVersionInput
import org.misarch.catalog.persistence.model.*
import org.misarch.catalog.persistence.repository.*
import org.misarch.catalog.util.orNoValueReceived
import org.misarch.common.event.EventPublisher
import org.springframework.stereotype.Service
import java.time.OffsetDateTime
import java.util.*

/**
 * Service for [ProductVariantVersionEntity]s
 *
 * @param repository the provided repository
 * @property productVariantRepository repository for [ProductVariantEntity]s
 * @property taxRateRepository repository for [TaxRateEntity]s
 * @property mediaRepository repository for [MediaEntity]s
 * @property productVariantVersionToMediaRepository repository for [ProductVariantVersionToMediaEntity]s
 * @property categoryCharacteristicValueService service for [CategoryCharacteristicValueEntity]s
 * @property eventPublisher publisher for events
 */
@Service
class ProductVariantVersionService(
    repository: ProductVariantVersionRepository,
    private val productVariantRepository: ProductVariantRepository,
    private val taxRateRepository: TaxRateRepository,
    private val mediaRepository: MediaRepository,
    private val productVariantVersionToMediaRepository: ProductVariantVersionToMediaRepository,
    private val categoryCharacteristicValueService: CategoryCharacteristicValueService,
    private val eventPublisher: EventPublisher
) : BaseService<ProductVariantVersionEntity, ProductVariantVersionRepository>(repository) {

    /**
     * Creates a product variant version
     * Also checks if the product variant exists
     *
     * @param input defines the product variant version to be created
     */
    suspend fun createProductVariantVersion(
        input: CreateProductVariantVersionInput
    ): ProductVariantVersionEntity {
        if (!productVariantRepository.existsById(input.productVariantId)) {
            throw IllegalArgumentException("Product variant with id ${input.productVariantId} does not exist.")
        }
        val productVariantVersion = createProductVariantVersionInternal(input, input.productVariantId)
        val productVariant = productVariantRepository.findById(input.productVariantId).orNoValueReceived()
        productVariant.currentVersion = productVariantVersion.id!!
        productVariantRepository.save(productVariant)
        eventPublisher.publishEvent(
            CatalogEvents.PRODUCT_VARIANT_VERSION_CREATED,
            productVariantVersion.toEventDTO(input.mediaIds.toSet())
        )
        return productVariantVersion
    }

    /**
     * Creates a product variant version without checking if the product variant exists
     *
     * @param input defines the product variant version to be created
     * @param productVariantId the id of the product variant
     * @return the created product variant version
     */
    suspend fun createProductVariantVersionInternal(
        input: ProductVariantVersionInput, productVariantId: UUID
    ): ProductVariantVersionEntity {
        if (!taxRateRepository.existsById(input.taxRateId)) {
            throw IllegalArgumentException("Tax rate with id ${input.taxRateId} does not exist")
        }
        checkMediasExist(input.mediaIds)
        val version = repository.findMaxVersionByProductVariantId(productVariantId)?.plus(1) ?: 1
        val productVariantVersion = ProductVariantVersionEntity(
            name = input.name,
            description = input.description,
            version = version,
            retailPrice = input.retailPrice,
            createdAt = OffsetDateTime.now(),
            canBeReturnedForDays = input.canBeReturnedForDays,
            productVariantId = productVariantId,
            taxRateId = input.taxRateId,
            weight = input.weight,
            id = null
        )
        val savedProductVariantVersion = repository.save(productVariantVersion)
        createProductVariantVersionRelatedEntities(input, savedProductVariantVersion)
        return savedProductVariantVersion
    }

    /**
     * Creates the related entities of a product variant version.
     * Links related medias and creates the category characteristic values
     *
     * @param input defines the product variant version to be created
     * @param savedProductVariantVersion the saved product variant version
     */
    private suspend fun createProductVariantVersionRelatedEntities(
        input: ProductVariantVersionInput,
        savedProductVariantVersion: ProductVariantVersionEntity
    ) {
        categoryCharacteristicValueService.upsertCategoryCharacteristicValues(
            input.categoricalCharacteristicValues,
            input.numericalCharacteristicValues,
            savedProductVariantVersion.id!!
        )
        val productVariantVersionToMedias = input.mediaIds.distinct().map {
            ProductVariantVersionToMediaEntity(
                productVariantVersionId = savedProductVariantVersion.id,
                mediaId = it,
                id = null
            )
        }
        productVariantVersionToMediaRepository.saveAll(productVariantVersionToMedias).toList()
    }

    /**
     * Checks if the medias exist
     *
     * @param mediaIds the ids of the medias
     * @throws IllegalArgumentException if a media does not exist
     */
    private suspend fun checkMediasExist(mediaIds: List<UUID>) {
        mediaIds.forEach { mediaId ->
            if (!mediaRepository.existsById(mediaId)) {
                throw IllegalArgumentException("Media with id $mediaId does not exist.")
            }
        }
    }

}
