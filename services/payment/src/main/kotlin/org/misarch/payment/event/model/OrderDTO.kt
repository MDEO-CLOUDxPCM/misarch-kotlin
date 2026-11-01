package org.misarch.payment.event.model

import java.util.UUID

/**
 * The parts of an order the payment needs
 *
 * @property id the id of the order
 * @property paymentInformationId the payment information to pay with
 * @property compensatableOrderAmount the amount to pay
 */
data class OrderDTO(
    val id: UUID,
    val paymentInformationId: UUID,
    val compensatableOrderAmount: Double,
)
