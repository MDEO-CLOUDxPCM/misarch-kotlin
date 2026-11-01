package org.misarch.shipment.graphql.federation

import org.misarch.common.graphql.DataLoaderEntityResolver
import org.misarch.shipment.graphql.dataloader.ShipmentDataLoader
import org.misarch.shipment.graphql.model.Shipment
import org.springframework.stereotype.Component

/**
 * Federated resolver for [Shipment]s.
 */
@Component
class ShipmentResolver : DataLoaderEntityResolver<Shipment>(Shipment::class, ShipmentDataLoader::class)
