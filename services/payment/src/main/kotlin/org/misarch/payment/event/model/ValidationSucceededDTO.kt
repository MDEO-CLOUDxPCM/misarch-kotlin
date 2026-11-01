package org.misarch.payment.event.model

/**
 * The discounts of an order were validated.
 *
 * @property order the order, kept and relayed in `payment-enabled` / `payment-failed`
 */
data class ValidationSucceededDTO(val order: OrderDTO)
