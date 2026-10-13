package org.misarch.`return`.graphql.federation

import com.expediagroup.graphql.generator.federation.execution.FederatedTypePromiseResolver
import graphql.schema.DataFetchingEnvironment
import org.misarch.`return`.graphql.dataloader.ReturnDataLoader
import org.misarch.`return`.graphql.model.Return
import org.misarch.common.graphql.dataLoader
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Federated resolver for [Return]s (batched by [ReturnDataLoader]; an unknown id fails only its own entry).
 *
 * Fix (docs/fixes/return.md): the original registered no resolver although `Return` is a resolvable `@key`, so
 * every `_entities(Return)` lookup (e.g. shipment's `Shipment.return { reason }` through the gateway) failed.
 * Like `OrderItem.returnedWith`, the lookup needs no authorization.
 */
@Component
class ReturnResolver : FederatedTypePromiseResolver<Return> {
    override val typeName: String
        get() = Return::class.simpleName!!

    override fun resolve(
        environment: DataFetchingEnvironment, representation: Map<String, Any>
    ): CompletableFuture<Return?> {
        val id = representation["id"] as String?
            ?: return CompletableFuture.completedFuture(null)
        return environment.dataLoader<UUID, Return?>(ReturnDataLoader::class.simpleName!!)
            .load(UUID.fromString(id))
    }
}
