package org.misarch.catalog.graphql.federation

import com.expediagroup.graphql.generator.federation.execution.FederatedTypePromiseResolver
import graphql.schema.DataFetchingEnvironment
import org.misarch.catalog.graphql.dataloader.ProductDataLoader
import org.misarch.catalog.graphql.model.Product
import org.misarch.common.graphql.dataLoader
import org.springframework.stereotype.Component
import java.util.*
import java.util.concurrent.CompletableFuture

/**
 * Federated resolver for [Product]s.
 */
@Component
class ProductResolver : FederatedTypePromiseResolver<Product> {
    override val typeName: String
        get() = Product::class.simpleName!!

    override fun resolve(
        environment: DataFetchingEnvironment, representation: Map<String, Any>
    ): CompletableFuture<Product?> {
        val id = representation["id"] as String?
        return if (id == null) {
            CompletableFuture.completedFuture(null)
        } else {
            environment.dataLoader<UUID, Product?>(ProductDataLoader::class.simpleName!!)
                .load(UUID.fromString(id))
        }
    }
}
