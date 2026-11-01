package org.misarch.payment.event.model

/**
 * Payload of `payment-enabled` and `payment-failed`.
 *
 * @property order the order as received in `discount/order/validation-succeeded`
 */
data class PaymentEventDTO(val order: OrderDTO)
