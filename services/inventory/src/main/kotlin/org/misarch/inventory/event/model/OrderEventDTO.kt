package org.misarch.inventory.event.model

/**
 * An event about an order (`payment-enabled`, `payment-failed`, `discount/order/validation-failed`); only the
 * order id is used
 *
 * @property order the order
 */
data class OrderEventDTO(val order: OrderReferenceDTO)
