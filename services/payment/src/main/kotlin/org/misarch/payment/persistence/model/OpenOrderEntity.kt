package org.misarch.payment.persistence.model

import io.r2dbc.postgresql.codec.Json
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

/**
 * Entity for the order a payment was created for, kept to relay it in `payment-enabled` / `payment-failed`
 *
 * @property paymentId the payment
 * @property orderId the order (unique: one payment per order)
 * @property orderJson the order as received in `discount/order/validation-succeeded`
 */
@Table
class OpenOrderEntity(
    @Id
    val paymentId: UUID,
    val orderId: UUID?,
    val orderJson: Json
)
