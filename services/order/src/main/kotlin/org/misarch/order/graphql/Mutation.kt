package org.misarch.order.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.server.operations.Mutation
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.graphql.authorizedUser
import org.misarch.order.graphql.input.CreateOrderInput
import org.misarch.order.graphql.input.PlaceOrderInput
import org.misarch.order.graphql.model.Order
import org.misarch.order.service.OrderService
import org.springframework.stereotype.Component

/**
 * Defines GraphQL mutations
 *
 * @property orderService the order service
 */
@Component
class Mutation(
    private val orderService: OrderService
) : Mutation {

    @GraphQLDescription("Creates an order with `OrderStatus::Pending`.")
    suspend fun createOrder(
        @GraphQLDescription("CreateOrderInput")
        input: CreateOrderInput,
        dfe: DataFetchingEnvironment
    ): Order {
        val authorizedUser = dfe.authorizedUser
        if (authorizedUser.id != input.userId) {
            authorizedUser.checkIsEmployee()
        }
        return orderService.createOrder(input, authorizedUser).toDTO()
    }

    @GraphQLDescription(
        "Places an existing order by changing its status to `OrderStatus::Placed`.\n\n" +
            "Adds optional payment authorization input to order DTO when placing order."
    )
    suspend fun placeOrder(
        @GraphQLDescription("PlaceOrderInput")
        input: PlaceOrderInput,
        dfe: DataFetchingEnvironment
    ): Order {
        return orderService.placeOrder(input, dfe.authorizedUser).toDTO()
    }

}
