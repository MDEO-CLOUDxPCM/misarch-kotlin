package org.misarch.order.graphql.dataloader

import org.misarch.common.graphql.dataloader.IdDataLoader
import org.misarch.order.graphql.model.Order
import org.misarch.order.persistence.model.OrderEntity
import org.misarch.order.persistence.repository.OrderRepository
import org.springframework.stereotype.Component

/**
 * Data loader for [Order]s
 */
@Component
class OrderDataLoader(
    repository: OrderRepository
) : IdDataLoader<Order, OrderEntity>(repository)
