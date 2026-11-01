package org.misarch.payment.provider

import org.misarch.payment.graphql.model.PaymentStatus
import java.util.UUID

/**
 * Input for updating the status of a payment
 *
 * @property paymentId the payment
 * @property status the new status of the payment
 */
data class UpdatePaymentStatusInput(
    val paymentId: UUID,
    val status: PaymentStatus
)
