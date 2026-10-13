package org.misarch.order.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.server.operations.Mutation
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.graphql.authorizedUserOrNull
import org.misarch.order.auth.authorizeUser
import org.misarch.order.graphql.input.CreateOrderInput
import org.misarch.order.graphql.input.PlaceOrderInput
import org.misarch.order.graphql.model.Order
import org.misarch.order.graphql.scalar.unsignedArgument
import org.misarch.order.service.OrderService
import org.springframework.stereotype.Component

/**
 * GraphQL mutations (mutation.rs). Not transactional, like the original: `placeOrder` keeps the PLACED status when
 * publishing the event fails afterwards.
 */
@Component
class Mutation(
    private val orderService: OrderService,
) : Mutation {

    @GraphQLDescription("Creates an order with `OrderStatus::Pending`.")
    suspend fun createOrder(
        @GraphQLDescription("CreateOrderInput")
        input: CreateOrderInput,
        dfe: DataFetchingEnvironment,
    ): Order {
        dfe.authorizeUser(input.userId)
        val order = orderService.createOrder(input, dfe.authorizedUserOrNull!!)
        return Order(order)
    }

    @GraphQLDescription(
        "Places an existing order by changing its status to `OrderStatus::Placed`.\n\n" +
            "Adds optional payment authorization input to order DTO when placing order."
    )
    suspend fun placeOrder(
        @GraphQLDescription("PlaceOrderInput")
        input: PlaceOrderInput,
        dfe: DataFetchingEnvironment,
    ): Order {
        // async-graphql parses (and range-checks) the input before the resolver runs
        val cvc = unsignedArgument(
            input.paymentAuthorization?.cvc, 65535,
            " (occurred while parsing \"PaymentAuthorizationInput\") (occurred while parsing \"PlaceOrderInput\")"
        )
        val order = orderService.queryOrder(input.id)
        dfe.authorizeUser(order.userId)
        return Order(orderService.placeOrder(input.id, cvc))
    }
}
