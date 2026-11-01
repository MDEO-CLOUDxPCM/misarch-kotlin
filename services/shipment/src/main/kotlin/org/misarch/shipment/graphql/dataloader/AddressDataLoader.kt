package org.misarch.shipment.graphql.dataloader

import org.misarch.common.graphql.dataloader.IdDataLoader
import org.misarch.shipment.graphql.model.Address
import org.misarch.shipment.persistence.model.AddressEntity
import org.misarch.shipment.persistence.repository.AddressRepository
import org.springframework.stereotype.Component

/**
 * Data loader for [Address]s. An unknown id fails only its own key, not the whole batch.
 */
@Component
class AddressDataLoader(
    repository: AddressRepository
) : IdDataLoader<Address, AddressEntity>(repository) {
}
