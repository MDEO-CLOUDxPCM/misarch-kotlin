package org.misarch.catalog.service

import org.misarch.catalog.event.CatalogEvents
import org.misarch.catalog.graphql.input.CreateCategoryInput
import org.misarch.catalog.persistence.model.CategoryEntity
import org.misarch.catalog.persistence.repository.CategoryRepository
import org.misarch.common.event.EventPublisher
import org.misarch.common.persistence.requiredId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Service for [CategoryEntity]s
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
        val id = savedCategory.requiredId
        val characteristicNames =
            input.categoricalCharacteristics.map { it.name } + input.numericalCharacteristics.map { it.name }
        val duplicates = characteristicNames.duplicates()
        require(duplicates.isEmpty()) { "Characteristic names must be unique. Duplicates: $duplicates" }
        input.categoricalCharacteristics.forEach {
            categoryCharacteristicService.addCategoricalCategoryCharacteristic(it, id)
        }
        input.numericalCharacteristics.forEach {
            categoryCharacteristicService.addNumericalCategoryCharacteristic(it, id)
        }
        eventPublisher.publishEvent(CatalogEvents.CATEGORY_CREATED, savedCategory.toEventDTO())
        return savedCategory
    }

}
