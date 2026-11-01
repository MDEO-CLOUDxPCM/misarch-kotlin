package org.misarch.catalog.service

import org.misarch.catalog.graphql.input.CategoricalCategoryCharacteristicInput
import org.misarch.catalog.graphql.input.CreateCategoricalCategoryCharacteristicInput
import org.misarch.catalog.graphql.input.CreateNumericalCategoryCharacteristicInput
import org.misarch.catalog.graphql.input.NumericalCategoryCharacteristicInput
import org.misarch.catalog.persistence.model.CategoryCharacteristicDiscriminator
import org.misarch.catalog.persistence.model.CategoryCharacteristicEntity
import org.misarch.catalog.persistence.model.CategoryEntity
import org.misarch.catalog.persistence.repository.CategoryCharacteristicRepository
import org.misarch.catalog.persistence.repository.CategoryRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.*

/**
 * Service for [CategoryCharacteristicEntity]s
 *
 * @property repository the repository of the service's entity
 * @property categoryRepository repository for [CategoryEntity]s
 */
@Service
class CategoryCharacteristicService(
    private val repository: CategoryCharacteristicRepository, private val categoryRepository: CategoryRepository
) {

    /**
     * Creates a categorical category characteristic
     * Also checks if the category exists
     *
     * @param input defines the characteristic to be created
     * @return the created characteristic
     */
    @Transactional
    suspend fun createCategoricalCategoryCharacteristic(input: CreateCategoricalCategoryCharacteristicInput): CategoryCharacteristicEntity {
        val categoryId = input.categoryId
        checkCategoryExists(categoryId)
        return createCategoricalCategoryCharacteristicInternal(input, categoryId)
    }

    /**
     * Creates a categorical category characteristic without checking if the category exists
     *
     * @param input defines the characteristic to be created
     * @param categoryId the id of the category
     * @return the created characteristic
     */
    suspend fun createCategoricalCategoryCharacteristicInternal(
        input: CategoricalCategoryCharacteristicInput, categoryId: UUID
    ): CategoryCharacteristicEntity {
        val categoryCharacteristic = CategoryCharacteristicEntity(
            discriminator = CategoryCharacteristicDiscriminator.CATEGORICAL,
            name = input.name,
            description = input.description,
            unit = null,
            categoryId = categoryId,
            id = null
        )
        return repository.save(categoryCharacteristic)
    }

    /**
     * Creates a numerical category characteristic
     * Also checks if the category exists
     *
     * @param input defines the characteristic to be created
     * @return the created characteristic
     */
    @Transactional
    suspend fun createNumericalCategoryCharacteristic(input: CreateNumericalCategoryCharacteristicInput): CategoryCharacteristicEntity {
        val categoryId = input.categoryId
        checkCategoryExists(categoryId)
        return createNumericalCategoryCharacteristicInternal(input, categoryId)
    }

    /**
     * Creates a numerical category characteristic without checking if the category exists
     *
     * @param input defines the characteristic to be created
     * @param categoryId the id of the category
     * @return the created characteristic
     */
    suspend fun createNumericalCategoryCharacteristicInternal(
        input: NumericalCategoryCharacteristicInput, categoryId: UUID
    ): CategoryCharacteristicEntity {
        val categoryCharacteristic = CategoryCharacteristicEntity(
            discriminator = CategoryCharacteristicDiscriminator.NUMERICAL,
            name = input.name,
            description = input.description,
            unit = input.unit,
            categoryId = categoryId,
            id = null
        )
        return repository.save(categoryCharacteristic)
    }

    /**
     * Checks if the category exists
     *
     * @param categoryId the id of the category
     * @throws IllegalArgumentException if the category does not exist
     */
    private suspend fun checkCategoryExists(categoryId: UUID) {
        require(categoryRepository.existsById(categoryId)) { "Category with id $categoryId does not exist." }
    }

}
