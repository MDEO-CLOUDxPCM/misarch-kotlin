package org.misarch.discount.event.model

import java.util.UUID

/**
 * Event that is published when an order has failed validation.
 *
 * @property order The order that has failed validation.
 * @property failingDiscountIds The IDs of the discounts that failed validation.
 */
data class ValidationFailedDTO(
    val order: OrderDTO,
    val failingDiscountIds: List<UUID>
)
