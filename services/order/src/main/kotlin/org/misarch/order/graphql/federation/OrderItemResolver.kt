package org.misarch.order.graphql.federation

import org.misarch.common.graphql.DataLoaderEntityResolver
import org.misarch.order.graphql.dataloader.OrderItemDataLoader
import org.misarch.order.graphql.model.OrderItem
import org.springframework.stereotype.Component

/**
 * Federated resolver for [OrderItem]s.
 */
@Component
class OrderItemResolver : DataLoaderEntityResolver<OrderItem>(OrderItem::class, OrderItemDataLoader::class)
