package org.misarch.order.graphql.federation

import org.misarch.common.graphql.DataLoaderEntityResolver
import org.misarch.order.graphql.dataloader.OrderDataLoader
import org.misarch.order.graphql.model.Order
import org.springframework.stereotype.Component

/**
 * Federated resolver for [Order]s.
 */
@Component
class OrderResolver : DataLoaderEntityResolver<Order>(Order::class, OrderDataLoader::class)
