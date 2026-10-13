package org.misarch.shipment.graphql.dataloader

import org.misarch.common.graphql.dataloader.IdDataLoader
import org.misarch.shipment.graphql.model.Shipment
import org.misarch.shipment.persistence.model.ShipmentEntity
import org.misarch.shipment.persistence.repository.ShipmentRepository
import org.springframework.stereotype.Component

/**
 * Data loader for [Shipment]s
 *
 * @param repository repository for [ShipmentEntity]s
 */
@Component
class ShipmentDataLoader(
    repository: ShipmentRepository
) : IdDataLoader<Shipment, ShipmentEntity>(repository) {

    /** An unknown id fails only its own key (`… : null`), not the whole batch (docs/fixes/shipment.md). */
    override val failMissingKeysIndividually: Boolean get() = true
}
