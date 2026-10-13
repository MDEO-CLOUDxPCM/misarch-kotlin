package org.misarch.shipment.graphql.federation

import com.expediagroup.graphql.generator.federation.execution.FederatedTypePromiseResolver
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.graphql.dataLoader
import org.misarch.shipment.graphql.dataloader.ShipmentDataLoader
import org.misarch.shipment.graphql.model.Shipment
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Federated resolver for [Shipment]s.
 */
@Component
class ShipmentResolver : FederatedTypePromiseResolver<Shipment> {
    override val typeName: String
        get() = Shipment::class.simpleName!!

    override fun resolve(
        environment: DataFetchingEnvironment, representation: Map<String, Any>
    ): CompletableFuture<Shipment?> {
        val id = representation["id"] as String?
        return if (id == null) {
            CompletableFuture.completedFuture(null)
        } else {
            environment.dataLoader<UUID, Shipment?>(ShipmentDataLoader::class.simpleName!!)
                .load(UUID.fromString(id))
        }
    }
}
