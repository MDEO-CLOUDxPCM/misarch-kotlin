package org.misarch.shoppingcart.event.model

import java.util.UUID

/**
 * Order item DTO used for events (only the fields this service reads)
 *
 * @property shoppingCartItemId id of the shopping cart item the order item was created from
 */
data class OrderItemDTO(
    val shoppingCartItemId: UUID
)
