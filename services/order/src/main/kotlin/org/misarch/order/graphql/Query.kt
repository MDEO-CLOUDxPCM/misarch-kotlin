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
     * Always fails: the original reads the MongoDB collection `order_items`, which is never written (order items only
     * exist embedded in orders). Reproduced, see docs/specs/order.md §5.3.
     */
    @GraphQLDescription("Retrieves order_item of specific UUID.")
    fun orderItem(
        @GraphQLDescription("UUID of order_item to retrieve.")
        id: UUID,
    ): OrderItem = throw orderItemNotFound(id)

    companion object {
        /** The error of every OrderItem lookup by id (query and entity resolver). */
        fun orderItemNotFound(id: UUID) = RustError(RustMessages.notFound(RustTypeNames.ORDER_ITEM, id))
    }
}
