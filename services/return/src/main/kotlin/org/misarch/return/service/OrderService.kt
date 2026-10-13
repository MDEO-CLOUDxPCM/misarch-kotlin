package org.misarch.`return`.service

import org.misarch.`return`.event.model.OrderDTO
import org.misarch.`return`.persistence.model.OrderEntity
import org.misarch.`return`.persistence.repository.OrderItemRepository
import org.misarch.`return`.persistence.repository.OrderRepository
import org.springframework.stereotype.Service

/**
 * Service for [OrderEntity]s
 *
 * @param repository the provided repository
 * @param orderItemRepository the repository for order items
 */
@Service
class OrderService(
    private val repository: OrderRepository,
    private val orderItemRepository: OrderItemRepository
) {

    /**
     * Registers an order and its items (idempotent upserts, runs in the event handler's transaction)
     *
     * @param orderDTO the order to register
     */
    suspend fun registerOrder(orderDTO: OrderDTO) {
        repository.createOrder(orderDTO.id, orderDTO.userId)
        orderDTO.orderItems.forEach {
            orderItemRepository.upsertOrderItemFromOrder(
                id = it.id,
                compensatableAmount = it.compensatableAmount,
                orderId = orderDTO.id,
                productVariantVersionId = it.productVariantVersionId
            )
        }
    }

}
