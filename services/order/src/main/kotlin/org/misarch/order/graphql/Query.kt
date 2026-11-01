package org.misarch.order.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.server.operations.Query
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.graphql.authorizedUser
import org.misarch.common.graphql.load
import org.misarch.order.graphql.dataloader.OrderDataLoader
import org.misarch.order.graphql.model.Order
import org.misarch.order.graphql.model.OrderItem
import org.misarch.order.service.OrderService
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Defines GraphQL queries
 */
@Component
class Query(
    private val orderService: OrderService
) : Query {

    @GraphQLDescription("Retrieves order of specific UUID.")
    fun order(
        @GraphQLDescription("UUID of order to retrieve.")
        id: UUID,
        dfe: DataFetchingEnvironment
    ): CompletableFuture<Order> {
        return dfe.load(OrderDataLoader::class, id).thenApply {
            checkIsOwnerOrEmployee(it.userId, dfe)
            it
        }
    }

    @GraphQLDescription("Retrieves order_item of specific UUID.")
    suspend fun orderItem(
        @GraphQLDescription("UUID of order_item to retrieve.")
        id: UUID,
        dfe: DataFetchingEnvironment
    ): OrderItem {
        return orderService.findOrderItem(id, dfe.authorizedUser).toDTO()
    }

    /** Only the user owning the order and employees may read it. */
    private fun checkIsOwnerOrEmployee(userId: UUID, dfe: DataFetchingEnvironment) {
        val authorizedUser = dfe.authorizedUser
        if (authorizedUser.id != userId) {
            authorizedUser.checkIsEmployee()
        }
    }

}
