package org.misarch.order.service

import kotlinx.coroutines.flow.toList
import org.misarch.common.event.EventPublisher
import org.misarch.common.graphql.AuthorizedUser
import org.misarch.common.persistence.Condition
import org.misarch.common.persistence.ConditionQueries
import org.misarch.common.persistence.findByIdOrThrow
import org.misarch.common.persistence.requiredId
import org.misarch.order.event.OrderEvents
import org.misarch.order.event.model.OrderItemDTO
import org.misarch.order.graphql.input.CreateOrderInput
import org.misarch.order.graphql.model.OrderStatus
import org.misarch.order.persistence.model.OrderEntity
import org.misarch.order.persistence.model.OrderItemEntity
import org.misarch.order.persistence.model.OrderItemToDiscountEntity
import org.misarch.order.persistence.model.OrderItemToDiscountKey
import org.misarch.order.persistence.repository.OrderItemRepository
import org.misarch.order.persistence.repository.OrderItemToDiscountRepository
import org.misarch.order.persistence.repository.OrderRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * Service for [OrderEntity]s: writes orders with their items and changes their status
 */
@Service
class OrderService(
    private val repository: OrderRepository,
    private val orderItemRepository: OrderItemRepository,
    private val orderItemToDiscountRepository: OrderItemToDiscountRepository,
    private val conditionQueries: ConditionQueries,
    private val eventPublisher: EventPublisher,
    private val clock: Clock
) {

    /**
     * Creates a pending order with its items (in their order) and the discounts of the items. The compensatable
     * amount of the order is the sum of its items plus the shipment fees.
     *
     * @param input the order to create
     * @param items the priced items
     * @param shipmentFees the shipment fees of the order
     * @return the created order
     */
    @Transactional
    suspend fun createOrder(input: CreateOrderInput, items: List<PricedOrderItem>, shipmentFees: Long): OrderEntity {
        val order = repository.save(
            OrderEntity(
                userId = input.userId,
                createdAt = currentTimestamp(clock),
                orderStatus = OrderStatus.PENDING,
                placedAt = null,
                rejectionReason = null,
                shipmentAddressId = input.shipmentAddressId,
                invoiceAddressId = input.invoiceAddressId,
                compensatableOrderAmount = items.sumOf { it.compensatableAmount } + shipmentFees,
                paymentInformationId = input.paymentInformationId,
                vatNumber = input.vatNumber,
                id = null
            )
        )
        val savedItems = orderItemRepository.saveAll(items.mapIndexed { position, item ->
            item.toEntity(order.requiredId, position, order.createdAt)
        }).toList()
        val itemDiscounts = savedItems.zip(items).flatMap { (savedItem, item) ->
            item.discounts.map { OrderItemToDiscountEntity(OrderItemToDiscountKey(savedItem.requiredId, it.id), it.discount) }
        }
        if (itemDiscounts.isNotEmpty()) {
            orderItemToDiscountRepository.saveAll(itemDiscounts).toList()
        }
        return order
    }

    /**
     * Places a pending order and publishes it (`order/order/created`) with its items and their discounts. The status
     * change is compare-and-set on PENDING: of concurrent placements only one succeeds.
     *
     * @param order the order to place
     * @param cvc the optional CVC of the payment authorization
     * @return the placed order
     */
    @Transactional
    suspend fun placeOrder(order: OrderEntity, cvc: Int?): OrderEntity {
        val placedAt = currentTimestamp(clock)
        check(repository.placeIfPending(order.requiredId, placedAt) == 1) { notPendingMessage(order.requiredId) }
        order.orderStatus = OrderStatus.PLACED
        order.placedAt = placedAt
        eventPublisher.publishEvent(OrderEvents.ORDER_CREATED, order.toEventDTO(orderItemDTOs(order.requiredId), cvc))
        return order
    }

    /**
     * Rejects a pending order (compare-and-set on PENDING)
     *
     * @param order the order to reject
     */
    suspend fun rejectOrder(order: OrderEntity) {
        check(repository.rejectIfPending(order.requiredId) == 1) { notPendingMessage(order.requiredId) }
    }

    /**
     * The order item with [id]; only the user owning its order and employees may read it
     *
     * @param id the order item
     * @param authorizedUser the user who reads it
     */
    suspend fun findOrderItem(id: UUID, authorizedUser: AuthorizedUser): OrderItemEntity {
        val orderItem = orderItemRepository.findByIdOrThrow(id)
        if (repository.findByIdOrThrow(orderItem.orderId).userId != authorizedUser.id) {
            authorizedUser.checkIsEmployee()
        }
        return orderItem
    }

    /** The event DTOs of the items of an order, with the ids of their discounts. */
    private suspend fun orderItemDTOs(orderId: UUID): List<OrderItemDTO> {
        val items = orderItemRepository.findByOrderIdOrderByPosition(orderId)
        val discountIds = if (items.isEmpty()) {
            emptyMap()
        } else {
            conditionQueries.findAll<OrderItemToDiscountEntity>(
                Condition.inArray("t.orderItemId", items.map { it.requiredId }), orderBy = listOf("t.discountId ASC")
            ).groupBy({ it.key.orderItemId }, { it.key.discountId })
        }
        return items.map { it.toEventDTO(discountIds[it.id] ?: emptyList()) }
    }

    private fun notPendingMessage(orderId: UUID) = "Order $orderId is not pending: it was already placed or rejected"

}
