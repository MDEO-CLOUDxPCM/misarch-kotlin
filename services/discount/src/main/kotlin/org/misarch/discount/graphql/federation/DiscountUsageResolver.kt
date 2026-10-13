package org.misarch.discount.graphql.federation

import com.expediagroup.graphql.generator.federation.execution.FederatedTypePromiseResolver
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.graphql.dataLoader
import org.misarch.discount.graphql.dataloader.DiscountUsageDataLoader
import org.misarch.discount.graphql.model.DiscountUsage
import org.springframework.stereotype.Component
import java.util.*
import java.util.concurrent.CompletableFuture

/**
 * Federated resolver for [DiscountUsage]s.
 */
@Component
class DiscountUsageResolver : FederatedTypePromiseResolver<DiscountUsage> {
    override val typeName: String
        get() = DiscountUsage::class.simpleName!!

    override fun resolve(
        environment: DataFetchingEnvironment, representation: Map<String, Any>
    ): CompletableFuture<DiscountUsage?> {
        val id = representation["id"] as String?
        return if (id == null) {
            CompletableFuture.completedFuture(null)
        } else {
            environment.dataLoader<UUID, DiscountUsage>(DiscountUsageDataLoader::class.simpleName!!)
                .load(UUID.fromString(id)).thenApply { it }
        }
    }
}
