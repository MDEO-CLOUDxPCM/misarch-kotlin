package org.misarch.catalog.graphql.dataloader

import kotlinx.coroutines.flow.toList
import org.misarch.catalog.graphql.model.CategoricalCategoryCharacteristic
import org.misarch.catalog.persistence.model.CategoryCharacteristicEntity
import org.misarch.catalog.persistence.repository.CategoryCharacteristicRepository
import org.misarch.common.graphql.dataloader.BaseIdDataLoader
import org.springframework.stereotype.Component
import java.util.*

/**
 * Data loader for [CategoricalCategoryCharacteristic]s
 * (a characteristic of the other type fails the whole batch with a ClassCastException, like in the original)
 *
 * @param repository repository for [CategoryCharacteristicEntity]s
 */
@Component
class CategoricalCategoryCharacteristicDataLoader(
    private val repository: CategoryCharacteristicRepository
) : BaseIdDataLoader<CategoricalCategoryCharacteristic, CategoryCharacteristicEntity>() {

    override suspend fun loadAll(ids: List<UUID>): List<CategoryCharacteristicEntity> {
        return repository.findAllById(ids).toList()
    }

    override fun toDTO(entity: CategoryCharacteristicEntity): CategoricalCategoryCharacteristic {
        return entity.toDTO() as CategoricalCategoryCharacteristic
    }

    override fun key(entity: CategoryCharacteristicEntity): UUID {
        return entity.id!!
    }
}
