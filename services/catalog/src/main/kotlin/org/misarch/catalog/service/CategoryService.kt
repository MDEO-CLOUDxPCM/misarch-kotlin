package org.misarch.catalog.service

import org.misarch.catalog.event.CatalogEvents
import org.misarch.catalog.graphql.input.CreateCategoryInput
import org.misarch.catalog.persistence.model.CategoryCharacteristicEntity
import org.misarch.catalog.persistence.model.CategoryEntity
import org.misarch.catalog.persistence.repository.CategoryRepository
import org.misarch.common.event.EventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Service for [CategoryEntity]s
 *
 * @property repository the repository of the service's entity
 * @property categoryCharacteristicService service for [CategoryCharacteristicEntity]s
 * @property eventPublisher publisher for events
 */
@Service
class CategoryService(
    private val repository: CategoryRepository,
    private val categoryCharacteristicService: CategoryCharacteristicService,
    private val eventPublisher: EventPublisher
) {

    /**
     * Creates a category, also creates the characteristics
     *
     * @param input defines the category (and characteristics) to be created
     * @return the created category
     */
    @Transactional
    suspend fun createCategory(input: CreateCategoryInput): CategoryEntity {
        val category = CategoryEntity(
            name = input.name, description = input.description, id = null
        )
        val savedCategory = repository.save(category)
        val id = savedCategory.id!!
        val characteristicNames =
            input.categoricalCharacteristics.map { it.name } + input.numericalCharacteristics.map { it.name }
        val duplicates = characteristicNames.duplicates()
        if (duplicates.isNotEmpty()) {
            throw IllegalArgumentException("Characteristic names must be unique. Duplicates: $duplicates")
        }
        input.categoricalCharacteristics.forEach {
            categoryCharacteristicService.createCategoricalCategoryCharacteristicInternal(it, id)
        }
        input.numericalCharacteristics.forEach {
            categoryCharacteristicService.createNumericalCategoryCharacteristicInternal(it, id)
        }
        eventPublisher.publishEvent(CatalogEvents.CATEGORY_CREATED, savedCategory.toEventDTO())
        return savedCategory
    }

}
