package org.misarch.invoice.event.model

/**
 * DTO of a successfully validated order (`discount/order/validation-succeeded`)
 *
 * @property order the validated order
 */
data class ValidationSucceededDTO(
    val order: OrderDTO
)
