package org.misarch.shipment.graphql.dataloader

import org.misarch.common.graphql.dataloader.IdDataLoader
import org.misarch.shipment.graphql.model.ShipmentMethod
import org.misarch.shipment.persistence.model.ShipmentMethodEntity
import org.misarch.shipment.persistence.repository.ShipmentMethodRepository
import org.springframework.stereotype.Component

/**
 * Data loader for [ShipmentMethod]s. An unknown id fails only its own key, not the whole batch.
 *
 * @param repository repository for [ShipmentMethodEntity]s
 */
@Component
class ShipmentMethodDataLoader(
    repository: ShipmentMethodRepository
) : IdDataLoader<ShipmentMethod, ShipmentMethodEntity>(repository) {
}
