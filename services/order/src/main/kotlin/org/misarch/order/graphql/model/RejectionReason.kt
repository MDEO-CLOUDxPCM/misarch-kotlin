package org.misarch.order.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription

@GraphQLDescription("Describes the reason why an order was rejected, in case of rejection: `OrderStatus::Rejected`.")
enum class RejectionReason {
    @GraphQLDescription("The order was rejected due to its invalid content.")
    INVALID_ORDER_DATA,

    @GraphQLDescription("The inventory service was not able to reserve inventory items according to the order.")
    INVENTORY_RESERVATION_FAILED
}
