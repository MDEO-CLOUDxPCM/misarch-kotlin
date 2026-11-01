package org.misarch.inventory.event.model

import java.util.UUID

/**
 * An event about an order (`payment-enabled`, `payment-failed`, `discount/order/validation-failed`); only the
 * order id is used
 *
 * @property order the order
 */
data class OrderEventDTO(val order: OrderReferenceDTO)

/**
 * Reference to an order
 *
 * @property id the id of the order
 */
data class OrderReferenceDTO(val id: UUID)
