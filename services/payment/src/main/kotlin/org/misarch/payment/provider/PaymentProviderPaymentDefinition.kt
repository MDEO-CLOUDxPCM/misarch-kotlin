package org.misarch.payment.provider

import java.util.UUID

/**
 * The payment registered at the payment provider, which calls back with the result
 * (`POST /payment/update-payment-status`)
 *
 * @property paymentId id of the payment
 * @property amount the amount to pay
 * @property paymentType `credit-card`, `prepayment` or `invoice`
 */
data class PaymentProviderPaymentDefinition(
    val paymentId: UUID,
    val amount: Long,
    val paymentType: String
)
