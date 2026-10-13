package org.misarch.shipment.graphql.federation

import com.expediagroup.graphql.generator.federation.execution.FederatedTypeSuspendResolver
import graphql.schema.DataFetchingEnvironment
import org.misarch.shipment.graphql.model.OrderItem
import org.misarch.shipment.persistence.repository.OrderItemRepository
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Federated resolver for [OrderItem]s (one query per representation, no data loader - like the original).
 * Unknown order items resolve to an [OrderItem] without shipment.
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
