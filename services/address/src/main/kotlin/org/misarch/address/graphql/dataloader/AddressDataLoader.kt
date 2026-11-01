package org.misarch.address.graphql.dataloader

import org.misarch.address.graphql.model.Address
import org.misarch.address.persistence.model.AddressEntity
import org.misarch.address.persistence.repository.AddressRepository
import org.misarch.common.graphql.dataloader.IdDataLoader
import org.springframework.stereotype.Component

/**
 * Data loader for [Address]es (user and vendor addresses). An unknown id fails only its own key, not the whole batch.
 *
 * @param repository repository for [AddressEntity]s
 */
@Component
class AddressDataLoader(
    repository: AddressRepository
) : IdDataLoader<Address, AddressEntity>(repository) {
}
