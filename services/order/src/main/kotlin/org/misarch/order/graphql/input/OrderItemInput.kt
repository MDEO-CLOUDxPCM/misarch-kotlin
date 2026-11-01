package org.misarch.order.graphql.input

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import java.util.UUID

/**
 * Input for an item of an order
 *
 * @property shoppingCartItemId the id of the shopping cart item to order
 * @property shipmentMethodId the id of the shipment method
 * @property couponIds the ids of the coupons to use
 */
class OrderItemInput(
    @property:GraphQLDescription("UUID of shopping cart item associated with order item.")
    val shoppingCartItemId: UUID,
    @property:GraphQLDescription("UUID of shipment method to use with order item.")
    val shipmentMethodId: UUID,
    @property:GraphQLDescription("UUIDs of coupons to use with order item.")
    val couponIds: List<UUID>
)
