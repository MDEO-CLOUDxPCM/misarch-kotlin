package org.misarch.`return`.service

import kotlinx.coroutines.flow.toList
import org.misarch.`return`.event.ReturnEvents
import org.misarch.`return`.graphql.input.CreateReturnInput
import org.misarch.`return`.persistence.model.OrderItemEntity
import org.misarch.`return`.persistence.model.ReturnEntity
import org.misarch.`return`.persistence.model.ShipmentEntity
import org.misarch.`return`.persistence.repository.OrderItemRepository
import org.misarch.`return`.persistence.repository.OrderRepository
import org.misarch.`return`.persistence.repository.ProductVariantVersionRepository
import org.misarch.`return`.persistence.repository.ReturnRepository
import org.misarch.`return`.persistence.repository.ShipmentRepository
import org.misarch.common.event.EventPublisher
import org.misarch.common.graphql.AuthorizedUser
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Service for [ReturnEntity]s
 *
 * @param repository the provided repository
 * @param orderItemRepository the order item repository
 * @param shipmentRepository the shipment repository
 * @param orderRepository the order repository
 * @param productVariantVersionRepository the product variant version repository
 * @param eventPublisher the event publisher
 */
@Service
class ReturnService(
    private val repository: ReturnRepository,
    private val orderItemRepository: OrderItemRepository,
    private val shipmentRepository: ShipmentRepository,
    private val orderRepository: OrderRepository,
    private val productVariantVersionRepository: ProductVariantVersionRepository,
    private val eventPublisher: EventPublisher
) {

    /**
     * Creates a return
     * Handles permission checking
     *
     * @param returnInput the return to create
     * @param authorizedUser the authorized user, used for permission checking
     * @return the created return
     */
    suspend fun createReturn(returnInput: CreateReturnInput, authorizedUser: AuthorizedUser): ReturnEntity {
        val orderItems = orderItemRepository.findAllById(returnInput.orderItemIds).toList()
        validateReturn(returnInput, orderItems, authorizedUser)
        val orderId = orderItems.first().orderId
        val refundedAmount = orderItems.sumOf { it.compensatableAmount!! }
        val returnEntity = ReturnEntity(
            reason = returnInput.reason,
            orderId = orderId,
            refundedAmount = refundedAmount,
            createdAt = OffsetDateTime.now(),
            id = null
        )
        val savedReturn = repository.save(returnEntity)
        for (orderItem in orderItems) {
            orderItem.returnedWithId = savedReturn.id
        }
        // full-row UPDATEs: the database triggers on OrderItemEntity (incl. "UPDATE OF returnedWithId") fire
        orderItemRepository.saveAll(orderItems).toList()
        eventPublisher.publishEvent(
            ReturnEvents.RETURN_CREATED, savedReturn.toEventDTO(returnInput.orderItemIds)
        )
        return savedReturn
    }

    /**
     * Checks that a return can be created
     * Checks that at least one order item is provided, and all order items exist, belong to the same order,
     * have been delivered and have not been returned yet.
     * The order of the checks (and therefore the error for a given input) is the original's: an empty or entirely
     * unknown id list fails in `first()` ("List is empty.") before the explicit checks.
     *
     * @param returnInput the return to validate
     * @param orderItems the order items to validate
     * @param authorizedUser the authorized user, used for permission checking
     */
    private suspend fun validateReturn(
        returnInput: CreateReturnInput, orderItems: List<OrderItemEntity>, authorizedUser: AuthorizedUser
    ) {
        val orderId = orderItems.first().orderId
        val order = orderRepository.findById(orderId) ?: throw NoSuchElementException()
        require(order.userId == authorizedUser.id) { "Only the original buyer can create returns" }
        require(returnInput.orderItemIds.isNotEmpty()) { "At least one order item id must be provided" }
        val missingOrderItemIds = returnInput.orderItemIds.toSet() - orderItems.map { it.id }.toSet()
        require(missingOrderItemIds.isEmpty()) { "Order item ids $missingOrderItemIds do not exist" }
        val returnedOrderItems = orderItems.filter { it.returnedWithId != null }
        require(returnedOrderItems.isEmpty()) { "Order item ids ${returnedOrderItems.map { it.id }} have already been returned" }
        require(orderItems.all { it.sentWithId != null && it.compensatableAmount != null && it.productVariantVersionId != null }) {
            "Missing data about order items, please try again later"
        }
        val originalShipments = orderItems.map { it.sentWithId!! }.toSet()
        val shipmentsById = shipmentRepository.findAllById(originalShipments).toList().associateBy { it.id }
        require(shipmentsById.values.all { it.deliveredAt != null }) { "At least one order item was not delivered" }
        require(orderItems.all { it.orderId == orderId }) { "All order items must belong to the same order" }
        validateItemsCanStillBeReturned(orderItems, shipmentsById)
    }

    /**
     * Validates that the items can still be returned
     *
     * @param orderItems the order items to validate
     * @param shipmentsById the shipments of the order items by id
     */
    private suspend fun validateItemsCanStillBeReturned(
        orderItems: List<OrderItemEntity>, shipmentsById: Map<UUID, ShipmentEntity>
    ) {
        val productVariantVersions = productVariantVersionRepository.findAllById(
            orderItems.map { it.productVariantVersionId!! }.toSet()
        ).toList().associateBy { it.id }
        for (orderItem in orderItems) {
            val productVariantVersion =
                productVariantVersions[orderItem.productVariantVersionId] ?: error("Product variant version not found")
            val canBeReturnedForDays = productVariantVersion.canBeReturnedForDays
            if (canBeReturnedForDays != null) {
                require(canBeReturnedForDays > 0) { "Order item ${orderItem.id} cannot be returned" }
                val passedDays =
                    Duration.between(shipmentsById[orderItem.sentWithId]!!.deliveredAt, OffsetDateTime.now()).toDays()
                require(passedDays <= canBeReturnedForDays) { "Order item ${orderItem.id} can no longer be returned" }
            }
        }
    }

}
