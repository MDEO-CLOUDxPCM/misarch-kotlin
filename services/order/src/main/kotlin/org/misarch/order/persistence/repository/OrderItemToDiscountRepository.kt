package org.misarch.order.persistence.repository

import org.misarch.order.persistence.model.OrderItemToDiscountEntity
import org.misarch.order.persistence.model.OrderItemToDiscountKey
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * Repository for [OrderItemToDiscountEntity]s
 */
interface OrderItemToDiscountRepository : CoroutineCrudRepository<OrderItemToDiscountEntity, OrderItemToDiscountKey>
