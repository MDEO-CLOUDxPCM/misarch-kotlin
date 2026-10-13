package org.misarch.common.graphql.dataloader

import kotlinx.coroutines.flow.toList
import org.misarch.common.persistence.BaseEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.UUID

/**
 * Id data loader for [BaseEntity]s stored by a [CoroutineCrudRepository]:
 * ```
 * @Component
 * class AddressDataLoader(repository: AddressRepository) : IdDataLoader<Address, AddressEntity>(repository)
 * ```
 */
abstract class IdDataLoader<T : Any, D : BaseEntity<T>>(
    private val repository: CoroutineCrudRepository<D, UUID>
) : BaseIdDataLoader<T, D>() {

    override suspend fun loadAll(ids: List<UUID>): List<D> = repository.findAllById(ids).toList()

    override fun toDTO(entity: D): T = entity.toDTO()

    override fun key(entity: D): UUID = entity.id!!
}
