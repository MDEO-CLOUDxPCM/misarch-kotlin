package org.misarch.address.graphql.dataloader

import org.misarch.address.graphql.model.Address
import org.misarch.address.persistence.model.AddressEntity
import org.misarch.address.persistence.repository.AddressRepository
import org.misarch.common.graphql.dataloader.IdDataLoader
import org.springframework.stereotype.Component

/**
 * Data loader for [Address]es (user and vendor addresses). FIXED (docs/fixes/address.md): an unknown id fails only its own key
 * (the original failed the whole batch with a NullPointerException).
 *
 * @param repository repository for [AddressEntity]s
 */
@Component
class AddressDataLoader(
    repository: AddressRepository
) : IdDataLoader<Address, AddressEntity>(repository) {
    override val failMissingKeysIndividually: Boolean = true
}
