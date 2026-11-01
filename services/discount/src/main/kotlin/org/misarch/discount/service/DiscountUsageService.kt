package org.misarch.discount.service

import kotlinx.coroutines.flow.toList
import org.misarch.common.event.EventPublisher
import org.misarch.common.persistence.requiredId
import org.misarch.discount.event.DiscountEvents
import org.misarch.discount.event.model.OrderDTO
import org.misarch.discount.event.model.ValidationFailedDTO
import org.misarch.discount.event.model.ValidationSucceededDTO
import org.misarch.discount.persistence.model.DiscountEntity
import org.misarch.discount.persistence.repository.DiscountRepository
import org.misarch.discount.persistence.repository.DiscountUsageRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Service for [org.misarch.discount.persistence.model.DiscountUsageEntity]s: how often a user has used a discount.
 */
@Service
class DiscountUsageService(
    private val repository: DiscountUsageRepository,
    private val discountRepository: DiscountRepository,
    private val eventPublisher: EventPublisher
) {

    /**
     * Validates an order
     * Checks if the user can still use the discounts with the amount of items
     * Does NOT validate if the discount is even usable with the items in the order
     * The usage upserts and the result event are one transaction.
     *
     * @param order the order to validate
     */
    @Transactional
    suspend fun validateOrder(order: OrderDTO) {
        val orderItemsByDiscount = order.orderItems.flatMap { orderItem ->
            orderItem.discountIds.map { it to orderItem }
        }.groupBy({ it.first }) { it.second }
        val discounts =
            discountRepository.findAllById(orderItemsByDiscount.keys).toList().associateBy { it.id }
        val failedDiscounts = mutableListOf<UUID>()
        val remainingDiscountUsages = findRemainingUsages(order.userId, discounts.values.toSet())
        orderItemsByDiscount.forEach { (discount, orderItems) ->
            val totalAmount = orderItems.sumOf { it.count }
            if (totalAmount > (remainingDiscountUsages[discount] ?: Long.MAX_VALUE)) {
                failedDiscounts += discount
            }
        }
        if (failedDiscounts.isNotEmpty()) {
            eventPublisher.publishEvent(DiscountEvents.VALIDATION_FAILED, ValidationFailedDTO(order, failedDiscounts))
        } else {
            orderItemsByDiscount.forEach { (discount, orderItems) ->
                repository.upsertDiscountUsage(discount, order.userId, orderItems.sumOf { it.count })
            }
            eventPublisher.publishEvent(DiscountEvents.VALIDATION_SUCCEEDED, ValidationSucceededDTO(order))
        }
    }

    /**
     * Finds the remaining usages for a user for a set of discounts
     * Only returns the remaining usages for discounts that have a maximum number of usages per user
     *
     * A discount without a per-user limit is unlimited and absent from the map.
     *
     * @param userId the id of the user
     * @param discounts the discounts to find the remaining usages for
     * @return the remaining usages for each discount that has a maximum number of usages per user
     */
    suspend fun findRemainingUsages(
        userId: UUID, discounts: Set<DiscountEntity>
    ): Map<UUID, Long> {
        val discountIds = discounts.filter { it.maxUsagesPerUser != null }.map { it.requiredId }.toSet()
        val currentUsagesByDiscount = repository.findByUserIdAndDiscountIdIn(userId, discountIds)
            .associateBy({ it.discountId }) { it.usages }
        return discounts.filter { it.maxUsagesPerUser != null }.associate {
            it.requiredId to it.maxUsagesPerUser!! - (currentUsagesByDiscount[it.id] ?: 0)
        }
    }
}
