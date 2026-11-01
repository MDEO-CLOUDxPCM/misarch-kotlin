package org.misarch.shoppingcart.event.model

import java.util.UUID

/**
 * Order DTO used for events (only the fields this service reads)
 *
 * @property id id of the order
 * @property userId id of the user who placed the order
 * @property orderItems the items of the order
 */
data class OrderDTO(
    val id: UUID,
    val userId: UUID,
    val orderItems: List<OrderItemDTO>
)
