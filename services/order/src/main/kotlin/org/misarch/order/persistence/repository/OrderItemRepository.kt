package org.misarch.order.persistence.repository

import org.misarch.order.persistence.model.OrderItemEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.UUID

/**
 * Repository for [OrderItemEntity]s
 */
interface OrderItemRepository : CoroutineCrudRepository<OrderItemEntity, UUID> {

    /**
     * Finds the items of an order in their order
     *
     * @param orderId the id of the order
     */
    suspend fun findByOrderIdOrderByPosition(orderId: UUID): List<OrderItemEntity>

}
