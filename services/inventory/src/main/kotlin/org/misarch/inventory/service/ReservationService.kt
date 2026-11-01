package org.misarch.inventory.service

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.misarch.common.event.EventPublisher
import org.misarch.inventory.event.InventoryEvents
import org.misarch.inventory.event.model.OrderDTO
import org.misarch.inventory.event.model.ReservationFailedDTO
import org.misarch.inventory.event.model.ReservationSucceededDTO
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * Reserves the product items of new orders and publishes the result
 *
 * @property productItemService reserves and releases the product items
 * @property eventPublisher publishes the reservation result
 * @property jsonMapper reads the order
 */
@Service
class ReservationService(
    private val productItemService: ProductItemService,
    private val eventPublisher: EventPublisher,
    private val jsonMapper: JsonMapper,
) {

    private val logger = LoggerFactory.getLogger(ReservationService::class.java)

    /**
     * Reserves the product items of all items of an order (each item concurrently, in its own transaction).
     * Publishes `reservation-succeeded` if all could be reserved; otherwise releases the items reserved for the order
     * and publishes `reservation-failed` with the product variants that failed. Idempotent: a redelivered event
     * reserves only what is missing and publishes the result again. A failing publish fails the event (redelivery).
     *
     * @param order the created order, relayed unchanged in the published event
     */
    suspend fun reserveProductItemsOfOrder(order: JsonNode) {
        val orderDTO = jsonMapper.treeToValue(order, OrderDTO::class.java)
        logger.info("Reserving product items for order {}", orderDTO.id)
        val failedProductVariantIds = coroutineScope {
            orderDTO.orderItems.map { item ->
                async {
                    try {
                        productItemService.reserveProductItemsForOrder(item.productVariantId, item.count, orderDTO.id)
                        null
                    } catch (e: IllegalStateException) {
                        logger.info("Reservation for order {} failed: {}", orderDTO.id, e.message)
                        item.productVariantId
                    }
                }
            }.awaitAll().filterNotNull()
        }
        if (failedProductVariantIds.isEmpty()) {
            eventPublisher.publishEvent(InventoryEvents.RESERVATION_SUCCEEDED, ReservationSucceededDTO(order))
        } else {
            productItemService.releaseProductItemsOfOrder(orderDTO.id)
            eventPublisher.publishEvent(
                InventoryEvents.RESERVATION_FAILED, ReservationFailedDTO(order, failedProductVariantIds)
            )
        }
    }

}
