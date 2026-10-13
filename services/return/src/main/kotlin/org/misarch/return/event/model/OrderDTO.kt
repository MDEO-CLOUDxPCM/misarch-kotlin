package org.misarch.`return`.event.model

import java.util.UUID

/**
 * DTO of an order of a user (only the fields used by the return service; the rest is ignored).
 *
 * @property id Order UUID.
 * @property userId UUID of user connected with Order.
 * @property orderItems List of OrderItems associated with the Order.
 */
data class OrderDTO(
    val id: UUID,
    val userId: UUID,
    val orderItems: List<OrderItemDTO>
)
