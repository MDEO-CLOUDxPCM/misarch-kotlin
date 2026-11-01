package org.misarch.shipment.graphql.dataloader

import org.misarch.common.graphql.dataloader.IdDataLoader
import org.misarch.shipment.graphql.model.Shipment
import org.misarch.shipment.persistence.model.ShipmentEntity
import org.misarch.shipment.persistence.repository.ShipmentRepository
import org.springframework.stereotype.Component

/**
 * Data loader for [Shipment]s. An unknown id fails only its own key, not the whole batch.
 */
@Component
class ShipmentDataLoader(
    repository: ShipmentRepository
) : IdDataLoader<Shipment, ShipmentEntity>(repository) {
}
