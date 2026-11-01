package org.misarch.inventory.service

import org.misarch.common.event.EventPublisher
import org.misarch.inventory.event.InventoryEvents
import org.misarch.inventory.event.model.OrderDTO
import org.misarch.inventory.event.model.ReservationFailedDTO
import org.misarch.inventory.event.model.ReservationSucceededDTO
import org.misarch.inventory.persistence.repository.ProductItemRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Reserves the product items of new orders and publishes the result.
 */
@Service
class ReservationService(
    private val repository: ProductItemRepository,
    private val eventPublisher: EventPublisher,
) {

    private val logger = LoggerFactory.getLogger(ReservationService::class.java)

    /**
     * Reserves the product items of all items of an order in one transaction. Publishes `reservation-succeeded` if
     * all could be reserved; otherwise puts the items of the order back into storage and publishes
     * `reservation-failed` with the product variants that failed. Idempotent: a redelivered event reserves only what
     * is missing and publishes the result again. A failing publish rolls everything back (redelivery).
     *
     * @param order the created order, relayed in the published event
     */
    @Transactional
    suspend fun reserveProductItemsOfOrder(order: OrderDTO) {
        logger.info("Reserving product items for order {}", order.id)
        val alreadyReserved = repository.findByOrderId(order.id).groupingBy { it.productVariantId }.eachCount()
        val failedProductVariantIds = mutableListOf<UUID>()
        for (item in order.orderItems) {
            val missing = (item.count - (alreadyReserved[item.productVariantId] ?: 0)).toInt()
            if (missing > 0 && repository.reserve(item.productVariantId, missing, order.id).size < missing) {
                failedProductVariantIds += item.productVariantId
            }
        }
        if (failedProductVariantIds.isEmpty()) {
            eventPublisher.publishEvent(InventoryEvents.RESERVATION_SUCCEEDED, ReservationSucceededDTO(order))
        } else {
            logger.info("Not enough product items for order {}: {}", order.id, failedProductVariantIds)
            repository.releaseByOrderId(order.id)
            eventPublisher.publishEvent(
                InventoryEvents.RESERVATION_FAILED, ReservationFailedDTO(order, failedProductVariantIds)
            )
        }
    }

}
