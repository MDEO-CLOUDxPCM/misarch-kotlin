package org.misarch.discount.event.model

/**
 * Event that is published when an order has been successfully reserved.
 *
 * @property order The order that has been successfully reserved.
 */
data class ReservationSucceededDTO(
    val order: OrderDTO
)
