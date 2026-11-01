package org.misarch.shipment.event.model

/**
 * DTO for payment enabled events
 *
 * @property order OrderDTO of the order, for which payment was enabled.
 */
data class PaymentEnabledDTO(
    val order: OrderDTO
)
