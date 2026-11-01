package org.misarch.order.graphql.dataloader

import org.misarch.common.graphql.dataloader.IdDataLoader
import org.misarch.order.graphql.model.OrderItem
import org.misarch.order.persistence.model.OrderItemEntity
import org.misarch.order.persistence.repository.OrderItemRepository
import org.springframework.stereotype.Component

/**
 * Data loader for [OrderItem]s
 */
@Component
class OrderItemDataLoader(
    repository: OrderItemRepository
) : IdDataLoader<OrderItem, OrderItemEntity>(repository)
