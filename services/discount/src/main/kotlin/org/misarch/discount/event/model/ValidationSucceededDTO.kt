package org.misarch.discount.event.model

import org.misarch.discount.event.model.order.OrderDTO

/**
 * Event that is published when an order has been successfully validated.
 *
 * @property order The order that has been successfully validated.
 */
data class ValidationSucceededDTO(
    val order: OrderDTO
)
