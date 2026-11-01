package org.misarch.order.graphql.input

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import java.util.UUID

/**
 * Input for placing an order
 *
 * @property id the id of the order
 * @property paymentAuthorization the optional payment authorization
 */
class PlaceOrderInput(
    @property:GraphQLDescription("UUID of order to place.")
    val id: UUID,
    @property:GraphQLDescription("Optional payment authorization data.")
    val paymentAuthorization: PaymentAuthorizationInput? = null
)
