package org.misarch.`return`.event.model

import java.util.UUID

/**
 * Return DTO used for events
 *
 * @property id id of the return
 * @property orderId id of the order of which the items are being returned
 * @property orderItemIds ids of the order items returned
 * @property reason the reason for the return
 * @property refundedAmount the amount of money refunded
 * @property createdAt the time the return was created
 */
data class ReturnDTO(
    val id: UUID,
    val orderId: UUID,
    val orderItemIds: List<UUID>,
    val reason: String,
    val refundedAmount: Long,
    val createdAt: String
)
