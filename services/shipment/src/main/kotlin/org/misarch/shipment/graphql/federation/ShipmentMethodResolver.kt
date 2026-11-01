package org.misarch.shipment.graphql.federation

import org.misarch.common.graphql.DataLoaderEntityResolver
import org.misarch.shipment.graphql.dataloader.ShipmentMethodDataLoader
import org.misarch.shipment.graphql.model.ShipmentMethod
import org.springframework.stereotype.Component

/**
 * Federated resolver for [ShipmentMethod]s.
 */
@Component
class ShipmentMethodResolver : DataLoaderEntityResolver<ShipmentMethod>(ShipmentMethod::class, ShipmentMethodDataLoader::class)
