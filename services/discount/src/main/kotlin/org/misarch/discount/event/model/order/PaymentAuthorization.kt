package org.misarch.discount.event.model.order

import com.fasterxml.jackson.annotation.JsonAlias

/**
 * Payment authorization data.
 *
 * Order publishes `{"cvc": n}`; the key `cVC` is accepted as well, so events that still carry it keep their CVC.
 *
 * @property cvc CVC/CVV number of 3-4 digits.
 */
data class PaymentAuthorization(
    @JsonAlias("cVC")
    val cvc: Int
)
