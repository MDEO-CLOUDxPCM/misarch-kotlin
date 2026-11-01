package org.misarch.payment.persistence.model

import org.misarch.payment.event.model.OrderDTO
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

/**
 * Entity for the order a payment was created for, relayed in `payment-enabled` / `payment-failed`
 *
 * @property paymentId the payment
 * @property orderId the order (unique: one payment per order)
 * @property order the order as received in `discount/order/validation-succeeded`
 */
@Table
class OpenOrderEntity(
    @Id
    val paymentId: UUID,
    val orderId: UUID?,
    @Column("orderJson")
    val order: OrderDTO
)
