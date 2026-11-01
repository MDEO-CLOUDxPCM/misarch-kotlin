package org.misarch.order.service

import kotlinx.coroutines.flow.toList
import org.misarch.common.event.EventPublisher
import org.misarch.order.event.OrderEvents
import org.misarch.order.event.model.ShipmentFailedDTO
import org.misarch.order.persistence.model.OrderCompensationEntity
import org.misarch.order.persistence.model.OrderCompensationToOrderItemEntity
import org.misarch.order.persistence.model.OrderCompensationToOrderItemKey
import org.misarch.order.persistence.repository.OrderCompensationRepository
import org.misarch.order.persistence.repository.OrderCompensationToOrderItemRepository
import org.misarch.order.persistence.repository.OrderItemRepository
import org.misarch.order.persistence.repository.OrderRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Service for [OrderCompensationEntity]s
 *
 * @param repository the repository for [OrderCompensationEntity]s
 * @param orderCompensationToOrderItemRepository the repository for the compensated items
 * @param orderRepository the repository for orders
 * @param orderItemRepository the repository for order items
 * @param eventPublisher publishes the compensation
 */
@Service
class OrderCompensationService(
    private val repository: OrderCompensationRepository,
    private val orderCompensationToOrderItemRepository: OrderCompensationToOrderItemRepository,
    private val orderRepository: OrderRepository,
    private val orderItemRepository: OrderItemRepository,
    private val eventPublisher: EventPublisher
) {

    /**
     * Compensates the items of a shipment that could not be created: the listed items of the order that are not
     * compensated yet, with the sum of their compensatable amounts. A failed return shipment (no order) and an event
     * whose items are all compensated already (e.g. a redelivery) are ignored.
     *
     * @param shipmentFailedDTO the failed shipment
     */
    @Transactional
    suspend fun compensateOrderItems(shipmentFailedDTO: ShipmentFailedDTO) {
        val orderId = shipmentFailedDTO.orderId ?: return
        if (!orderRepository.existsById(orderId)) {
            throw NoSuchElementException()
        }
        val compensated = orderCompensationToOrderItemRepository.findByOrderId(orderId).map { it.key.orderItemId }.toSet()
        val items = orderItemRepository.findByOrderIdOrderByPosition(orderId)
            .filter { it.id in shipmentFailedDTO.orderItemIds && it.id !in compensated }
        if (items.isEmpty()) {
            return
        }
        val compensation = repository.save(
            OrderCompensationEntity(
                orderId = orderId,
                triggeredAt = currentTimestamp(),
                amountToCompensate = items.sumOf { it.compensatableAmount },
                id = null
            )
        )
        orderCompensationToOrderItemRepository.saveAll(items.mapIndexed { position, item ->
            OrderCompensationToOrderItemEntity(OrderCompensationToOrderItemKey(compensation.id!!, item.id!!), position)
        }).toList()
        eventPublisher.publishEvent(
            OrderEvents.ORDER_COMPENSATION_CREATED, compensation.toEventDTO(items.map { it.id!! })
        )
    }

}
