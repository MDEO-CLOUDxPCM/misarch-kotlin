package org.misarch.catalog.graphql.dataloader

import kotlinx.coroutines.flow.toList
import org.misarch.catalog.graphql.model.CategoricalCategoryCharacteristic
import org.misarch.catalog.persistence.model.CategoryCharacteristicDiscriminator
import org.misarch.catalog.persistence.model.CategoryCharacteristicEntity
import org.misarch.catalog.persistence.repository.CategoryCharacteristicRepository
import org.misarch.common.graphql.dataloader.BaseIdDataLoader
import org.springframework.stereotype.Component
import java.util.*

/**
 * Data loader for [CategoricalCategoryCharacteristic]s. An unknown id, or the id of a numerical characteristic,
 * fails only its own key with `NoSuchElementException()`, not the whole batch.
 *
 * @param repository repository for [CategoryCharacteristicEntity]s
 */
@Component
class CategoricalCategoryCharacteristicDataLoader(
    private val repository: CategoryCharacteristicRepository
) : BaseIdDataLoader<CategoricalCategoryCharacteristic, CategoryCharacteristicEntity>() {


    override suspend fun loadAll(ids: List<UUID>): List<CategoryCharacteristicEntity> {
        return repository.findAllById(ids).toList()
            .filter { it.discriminator == CategoryCharacteristicDiscriminator.CATEGORICAL }
    }

    override fun toDTO(entity: CategoryCharacteristicEntity): CategoricalCategoryCharacteristic {
        return entity.toDTO() as CategoricalCategoryCharacteristic
    }

    override fun key(entity: CategoryCharacteristicEntity): UUID {
        return entity.id!!
    }
}
