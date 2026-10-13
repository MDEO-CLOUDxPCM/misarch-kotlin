package org.misarch.catalog.graphql.federation

import com.expediagroup.graphql.generator.federation.execution.FederatedTypePromiseResolver
import graphql.schema.DataFetchingEnvironment
import org.misarch.catalog.graphql.dataloader.ProductVariantDataLoader
import org.misarch.catalog.graphql.model.ProductVariant
import org.misarch.common.graphql.dataLoader
import org.springframework.stereotype.Component
import java.util.*
import java.util.concurrent.CompletableFuture

/**
 * Federated resolver for [ProductVariant]s.
 */
@Component
class ProductVariantResolver : FederatedTypePromiseResolver<ProductVariant> {
    override val typeName: String
        get() = ProductVariant::class.simpleName!!

    override fun resolve(
        environment: DataFetchingEnvironment, representation: Map<String, Any>
    ): CompletableFuture<ProductVariant?> {
        val id = representation["id"] as String?
        return if (id == null) {
            CompletableFuture.completedFuture(null)
        } else {
            environment.dataLoader<UUID, ProductVariant?>(ProductVariantDataLoader::class.simpleName!!)
                .load(UUID.fromString(id))
        }
    }
}
