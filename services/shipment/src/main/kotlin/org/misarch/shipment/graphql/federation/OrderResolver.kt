package org.misarch.shipment.graphql.federation

import org.misarch.common.graphql.ReferenceEntityResolver
import org.misarch.shipment.graphql.model.Order
import org.springframework.stereotype.Component

/**
 * Federated resolver for [Order]s (stub, no database access).
 */
@Component
class OrderResolver : ReferenceEntityResolver<Order>(Order::class, { Order(it) })
