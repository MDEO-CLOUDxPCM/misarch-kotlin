package org.misarch.discount.event.model.order

import com.fasterxml.jackson.annotation.JsonAlias

/**
 * Payment authorization data.
 *
 * FIXED (docs/fixes/discount.md, cvc): order now publishes `{"cvc": n}` (docs/fixes/order.md); the former key `cVC` of
 * the Rust original is still accepted, so the CVC is no longer lost (the original read it as 0).
 *
 * @property cvc CVC/CVV number of 3-4 digits.
 */
data class PaymentAuthorization(
    @JsonAlias("cVC")
    val cvc: Int
)
