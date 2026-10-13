package org.misarch.discount.graphql.federation

import com.expediagroup.graphql.generator.federation.execution.FederatedTypePromiseResolver
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.graphql.dataLoader
import org.misarch.discount.graphql.dataloader.DiscountDataLoader
import org.misarch.discount.graphql.model.Discount
import org.springframework.stereotype.Component
import java.util.*
import java.util.concurrent.CompletableFuture

/**
 * Federated resolver for [Discount]s.
 */
@Component
class DiscountResolver : FederatedTypePromiseResolver<Discount> {
    override val typeName: String
        get() = Discount::class.simpleName!!

    override fun resolve(
        environment: DataFetchingEnvironment, representation: Map<String, Any>
    ): CompletableFuture<Discount?> {
        val id = representation["id"] as String?
        return if (id == null) {
            CompletableFuture.completedFuture(null)
        } else {
            environment.dataLoader<UUID, Discount>(DiscountDataLoader::class.simpleName!!)
                .load(UUID.fromString(id)).thenApply { it }
        }
    }
}
