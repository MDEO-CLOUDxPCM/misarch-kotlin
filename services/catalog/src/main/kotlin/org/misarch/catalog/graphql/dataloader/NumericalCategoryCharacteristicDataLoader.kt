package org.misarch.catalog.graphql.dataloader

import kotlinx.coroutines.flow.toList
import org.misarch.catalog.graphql.model.NumericalCategoryCharacteristic
import org.misarch.catalog.persistence.model.CategoryCharacteristicDiscriminator
import org.misarch.catalog.persistence.model.CategoryCharacteristicEntity
import org.misarch.catalog.persistence.repository.CategoryCharacteristicRepository
import org.misarch.common.graphql.dataloader.BaseIdDataLoader
import org.misarch.common.persistence.requiredId
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Data loader for [NumericalCategoryCharacteristic]s. An unknown id, or the id of a categorical characteristic,
 * fails only its own key with `NoSuchElementException()`, not the whole batch.
 */
@Component
class NumericalCategoryCharacteristicDataLoader(
    private val repository: CategoryCharacteristicRepository
) : BaseIdDataLoader<NumericalCategoryCharacteristic, CategoryCharacteristicEntity>() {


    override suspend fun loadAll(ids: List<UUID>): List<CategoryCharacteristicEntity> {
        return repository.findAllById(ids).toList()
            .filter { it.discriminator == CategoryCharacteristicDiscriminator.NUMERICAL }
    }

    override fun toDTO(entity: CategoryCharacteristicEntity): NumericalCategoryCharacteristic {
        return entity.toDTO() as NumericalCategoryCharacteristic
    }

    override fun key(entity: CategoryCharacteristicEntity): UUID {
        return entity.requiredId
    }
}
