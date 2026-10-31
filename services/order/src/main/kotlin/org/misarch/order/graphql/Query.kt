package org.misarch.order.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.server.operations.Query
import graphql.schema.DataFetchingEnvironment
import org.misarch.order.auth.authorizeUser
import org.misarch.order.graphql.model.Order
import org.misarch.order.graphql.model.OrderItem
import org.misarch.order.rust.RustError
import org.misarch.order.rust.RustMessages
import org.misarch.order.rust.RustTypeNames
import org.misarch.order.service.OrderService
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * GraphQL queries (query.rs).
 */
@Component
class Query(
    private val orderService: OrderService,
) : Query {

    @GraphQLDescription("Retrieves order of specific UUID.")
    suspend fun order(
        @GraphQLDescription("UUID of order to retrieve.")
        id: UUID,
        dfe: DataFetchingEnvironment,
    ): Order {
        val order = orderService.queryOrder(id)
        dfe.authorizeUser(order.userId)
        return Order(order)
    }

    /**
     * The order item (its `order_items` row), authorized against the user of its order (the order's row). FIXED (docs/fixes/order.md): the original read the
     * never-written MongoDB collection `order_items`, so it always failed with the not-found error.
     */
    @GraphQLDescription("Retrieves order_item of specific UUID.")
    suspend fun orderItem(
        @GraphQLDescription("UUID of order_item to retrieve.")
        id: UUID,
        dfe: DataFetchingEnvironment,
    ): OrderItem {
        val item = orderService.queryOrderItem(id)
        dfe.authorizeUser(orderService.queryOrder(item.orderId).userId)
        return OrderItem(item)
    }
}
