package org.misarch.inventory.event.model

import java.util.UUID

/**
 * The parts of an order the reservation needs (the order itself is relayed unchanged)
 *
 * @property id the id of the order
 * @property orderItems the items of the order
 */
data class OrderDTO(
    val id: UUID,
    val orderItems: List<OrderItemDTO>,
)

/**
 * An item of an order
 *
 * @property productVariantId the product variant of the item
 * @property count the number of product items to reserve
 */
data class OrderItemDTO(
    val productVariantId: UUID,
    val count: Int,
)
