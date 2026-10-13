package org.misarch.order.graphql.federation

import com.expediagroup.graphql.generator.federation.execution.FederatedTypeSuspendResolver
import graphql.GraphqlErrorBuilder
import graphql.schema.DataFetchingEnvironment
import org.misarch.order.graphql.model.Order
import org.misarch.order.graphql.model.OrderItem
import org.misarch.order.graphql.model.User
import org.misarch.order.persistence.OrderStore
import org.misarch.order.rust.RustMessages
import org.misarch.order.rust.RustTypeNames
import org.misarch.common.rust.RustUuid
import org.misarch.order.service.OrderService
import org.springframework.stereotype.Component
import java.util.UUID

/*
 * Entity resolvers of the original (query.rs, `#[graphql(entity)]`), none of them authorizes. A failing lookup is
 * returned as a GraphQL error with the original's plain message: graphql-kotlin turns a `GraphQLError` result of a
 * resolver into a `null` entity plus that error (an exception would be wrapped into
 * "Exception was thrown while trying to resolve federated type ...").
 */

private fun entityError(message: String) = GraphqlErrorBuilder.newError().message(message).build()

private fun Map<String, Any>.id(): UUID? = (this["id"] as? String)?.let { RustUuid.tryParse(it) }

/** `order_entity_resolver`. */
@Component
class OrderResolver(private val orderService: OrderService) : FederatedTypeSuspendResolver<Any> {
    override val typeName: String = "Order"

    override suspend fun resolve(environment: DataFetchingEnvironment, representation: Map<String, Any>): Any {
        val id = representation.id() ?: return entityError("Failed to parse \"UUID\"")
        return try {
            Order(orderService.queryOrder(id))
        } catch (e: org.misarch.order.rust.RustError) {
            entityError(e.message!!)
        }
    }
}

/**
 * `order_item_entity_resolver` (no authorization). FIXED (docs/fixes/order.md): the original read the never-written
 * `order_items` collection and always failed.
 */
@Component
class OrderItemResolver(private val orderService: OrderService) : FederatedTypeSuspendResolver<Any> {
    override val typeName: String = "OrderItem"

    override suspend fun resolve(environment: DataFetchingEnvironment, representation: Map<String, Any>): Any {
        val id = representation.id() ?: return entityError("Failed to parse \"UUID\"")
        return try {
            OrderItem(orderService.queryOrderItem(id).second)
        } catch (e: org.misarch.order.rust.RustError) {
            entityError(e.message!!)
        }
    }
}

/** `user_entity_resolver`: the user must exist in the replica. */
@Component
class UserResolver(private val store: OrderStore) : FederatedTypeSuspendResolver<Any> {
    override val typeName: String = "User"

    override suspend fun resolve(environment: DataFetchingEnvironment, representation: Map<String, Any>): Any {
        val id = representation.id() ?: return entityError("Failed to parse \"UUID\"")
        return if (store.userExists(id)) User(id) else entityError(RustMessages.notFound(RustTypeNames.USER, id))
    }
}
