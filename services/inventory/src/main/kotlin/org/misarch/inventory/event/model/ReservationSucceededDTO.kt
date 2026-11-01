package org.misarch.inventory.event.model

/**
 * All product items of an order have been reserved.
 *
 * @property order the order as received in `order/order/created`
 */
data class ReservationSucceededDTO(val order: OrderDTO)
