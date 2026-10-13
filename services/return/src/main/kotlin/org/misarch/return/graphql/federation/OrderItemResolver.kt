package org.misarch.`return`.graphql.federation

import com.expediagroup.graphql.generator.federation.execution.FederatedTypeSuspendResolver
import graphql.schema.DataFetchingEnvironment
import org.misarch.`return`.graphql.model.OrderItem
import org.misarch.`return`.persistence.repository.OrderItemRepository
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Federated resolver for [OrderItem]s (one query per representation, unknown ids resolve to a stub).
 */
@Component
class OrderItemResolver(
    private val orderItemRepository: OrderItemRepository
) : FederatedTypeSuspendResolver<OrderItem> {
    override val typeName: String
        get() = OrderItem::class.simpleName!!

    override suspend fun resolve(
        environment: DataFetchingEnvironment, representation: Map<String, Any>
    ): OrderItem? {
        val id = representation["id"] as String?
        val uuid = id?.let { UUID.fromString(it) }
        return if (id == null) {
            null
        } else {
            orderItemRepository.findById(uuid!!)?.toDTO() ?: OrderItem(uuid, null)
        }
    }
}
