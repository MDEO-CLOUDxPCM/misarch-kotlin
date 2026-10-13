package org.misarch.shipment.graphql.federation

import com.expediagroup.graphql.generator.federation.execution.FederatedTypePromiseResolver
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.graphql.dataLoader
import org.misarch.shipment.graphql.dataloader.ShipmentMethodDataLoader
import org.misarch.shipment.graphql.model.ShipmentMethod
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Federated resolver for [ShipmentMethod]s.
 */
@Component
class ShipmentMethodResolver : FederatedTypePromiseResolver<ShipmentMethod> {
    override val typeName: String
        get() = ShipmentMethod::class.simpleName!!

    override fun resolve(
        environment: DataFetchingEnvironment, representation: Map<String, Any>
    ): CompletableFuture<ShipmentMethod?> {
        val id = representation["id"] as String?
        return if (id == null) {
            CompletableFuture.completedFuture(null)
        } else {
            environment.dataLoader<UUID, ShipmentMethod?>(ShipmentMethodDataLoader::class.simpleName!!)
                .load(UUID.fromString(id))
        }
    }
}
