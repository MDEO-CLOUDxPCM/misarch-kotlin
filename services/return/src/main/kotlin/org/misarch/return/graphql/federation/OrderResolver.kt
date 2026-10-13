package org.misarch.`return`.graphql.federation

import com.expediagroup.graphql.generator.federation.execution.FederatedTypePromiseResolver
import graphql.schema.DataFetchingEnvironment
import org.misarch.`return`.graphql.model.Order
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Federated resolver for [Order]s (no database access).
 */
@Component
class OrderResolver : FederatedTypePromiseResolver<Order> {
    override val typeName: String
        get() = Order::class.simpleName!!

    override fun resolve(
        environment: DataFetchingEnvironment, representation: Map<String, Any>
    ): CompletableFuture<Order?> {
        val id = representation["id"] as String?
        val uuid = UUID.fromString(id)
        return CompletableFuture.completedFuture(Order(uuid))
    }
}
