package org.misarch.order.event.model

import java.time.OffsetDateTime
import java.util.UUID

/**
 * DTO of an order compensation (`order/order-compensation/created`)
 *
 * @property id unique identifier of the compensation
 * @property orderId the id of the order
 * @property orderItemIds the ids of the compensated order items
 * @property triggeredAt the date and time the compensation was triggered
 * @property amountToCompensate the amount to compensate
 */
data class OrderCompensationDTO(
    val id: UUID,
    val orderId: UUID,
    val orderItemIds: List<UUID>,
    val triggeredAt: OffsetDateTime,
    val amountToCompensate: Long
)
