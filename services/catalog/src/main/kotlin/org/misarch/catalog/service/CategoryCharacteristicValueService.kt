package org.misarch.catalog.service

import org.misarch.catalog.graphql.input.CategoricalCategoryCharacteristicValueInput
import org.misarch.catalog.graphql.input.NumericalCategoryCharacteristicValueInput
import org.misarch.catalog.persistence.model.CategoryCharacteristicDiscriminator
import org.misarch.catalog.persistence.model.CategoryCharacteristicValueEntity
import org.misarch.catalog.persistence.repository.CategoryCharacteristicValueRepository
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Service for [CategoryCharacteristicValueEntity]s
 */
@Service
class CategoryCharacteristicValueService(
    private val repository: CategoryCharacteristicValueRepository
) {

    /**
     * Upserts the values for the given characteristics
     *
     * @param categoricalValues the values for the categorical characteristics
     * @param numericalValues the values for the numerical characteristics
     * @param productVariantVersionId the id of the product variant version where the values should be upserted
     */
    suspend fun upsertCategoryCharacteristicValues(
        categoricalValues: List<CategoricalCategoryCharacteristicValueInput>,
        numericalValues: List<NumericalCategoryCharacteristicValueInput>,
        productVariantVersionId: UUID
    ) {
        val categoricalUUIDs = categoricalValues.map { it.characteristicId }
        val numericalUUIDs = numericalValues.map { it.characteristicId }
        validateCharacteristicIdsForUpsert(productVariantVersionId, categoricalUUIDs, numericalUUIDs)
        categoricalValues.forEach {
            repository.upsert(
                it.characteristicId,
                productVariantVersionId,
                it.value,
                null,
                CategoryCharacteristicDiscriminator.CATEGORICAL.name
            )
        }
        numericalValues.forEach {
            repository.upsert(
                it.characteristicId,
                productVariantVersionId,
                null,
                it.value,
                CategoryCharacteristicDiscriminator.NUMERICAL.name
            )
        }
    }

    /**
     * Validates the characteristic ids for upserting values.
     * Checks that
     * - there are no duplicates
     * - all ids are valid (can be used with [productVariantVersionId])
     * - all categorical ids are in [categoricalUUIDs]
     * - all numerical ids are in [numericalUUIDs]
     *
     * @param productVariantVersionId the id of the product variant version
     * @param categoricalUUIDs the ids of the categorical characteristics
     * @param numericalUUIDs the ids of the numerical characteristics
     * @throws IllegalArgumentException if any of the checks fails
     */
    private suspend fun validateCharacteristicIdsForUpsert(
        productVariantVersionId: UUID, categoricalUUIDs: List<UUID>, numericalUUIDs: List<UUID>
    ) {
        val allValueUUIDs = categoricalUUIDs + numericalUUIDs
        if (allValueUUIDs.isEmpty()) {
            return
        }
        val duplicates = allValueUUIDs.duplicates()
        require(duplicates.isEmpty()) { "Duplicate characteristic ids: $duplicates" }
        val validValues = repository.findValidCategoryCharacteristics(productVariantVersionId, allValueUUIDs)
        val invalidValues = allValueUUIDs.toSet() - validValues.map { it.id!! }.toSet()
        require(invalidValues.isEmpty()) { "Invalid characteristic ids which cannot be used here: $invalidValues" }
        validValues.forEach {
            val valid = when (it.discriminator) {
                CategoryCharacteristicDiscriminator.CATEGORICAL -> it.id in categoricalUUIDs
                CategoryCharacteristicDiscriminator.NUMERICAL -> it.id in numericalUUIDs
            }
            require(valid) { "Characteristic id ${it.id} is not valid for this type of characteristic." }
        }
    }

}
