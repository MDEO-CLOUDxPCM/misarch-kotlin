package org.misarch.inventory.event.model

import com.fasterxml.jackson.annotation.JsonAlias

/**
 * DTO of the payment authorization of a placed order; the key `cVC` is accepted as well.
 *
 * @property cvc the CVC/CVV number
 */
data class PaymentAuthorizationDTO(
    @JsonAlias("cVC")
    val cvc: Int
)
