package org.misarch.payment.event

/**
 * Constants for the event topics used by the payment service
 */
object PaymentEvents {
    /**
     * Topic for successful discount validations of an order (a payment is created for the order)
     */
    const val ORDER_VALIDATION_SUCCEEDED = "discount/order/validation-succeeded"

    /**
     * Topic for user creation events (the default payment informations are created)
     */
    const val USER_CREATED = "user/user/created"

    /**
     * Topic for payment enabled events (the order can be shipped)
     */
    const val PAYMENT_ENABLED = "payment/payment/payment-enabled"

    /**
     * Topic for payment failed events (the order cannot be paid)
     */
    const val PAYMENT_FAILED = "payment/payment/payment-failed"
}
