package org.misarch.catalog.graphql.dataloader

import org.misarch.catalog.graphql.model.CategoryCharacteristic
import org.misarch.catalog.persistence.model.CategoryCharacteristicEntity
import org.misarch.catalog.persistence.repository.CategoryCharacteristicRepository
import org.misarch.common.graphql.dataloader.IdDataLoader
import org.springframework.stereotype.Component

/**
 * Data loader for [CategoryCharacteristic]s. An unknown id fails only its own key, not the whole batch.
 */
@Component
class CategoryCharacteristicDataLoader(
    repository: CategoryCharacteristicRepository
) : IdDataLoader<CategoryCharacteristic, CategoryCharacteristicEntity>(repository) {
}
