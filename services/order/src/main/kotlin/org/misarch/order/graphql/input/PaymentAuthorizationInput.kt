package org.misarch.order.graphql.input

import com.expediagroup.graphql.generator.annotations.GraphQLDescription

/**
 * Input for the payment authorization of an order
 *
 * @property cvc the CVC/CVV number
 */
class PaymentAuthorizationInput(
    @property:GraphQLDescription("CVC/CVV number of 3-4 digits.")
    val cvc: Int? = null
)
