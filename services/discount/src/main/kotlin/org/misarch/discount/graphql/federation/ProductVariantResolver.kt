package org.misarch.discount.graphql.federation

import com.expediagroup.graphql.generator.federation.execution.FederatedTypePromiseResolver
import graphql.schema.DataFetchingEnvironment
import org.misarch.discount.graphql.model.ProductVariant
import org.springframework.stereotype.Component
import java.util.*
import java.util.concurrent.CompletableFuture

/**
 * Federated resolver for [ProductVariant]s (no database access: the reference only carries the id).
 */
@Component
class ProductVariantResolver : FederatedTypePromiseResolver<ProductVariant> {
    override val typeName: String
        get() = ProductVariant::class.simpleName!!

    override fun resolve(
        environment: DataFetchingEnvironment, representation: Map<String, Any>
    ): CompletableFuture<ProductVariant?> {
        val id = representation["id"] as String?
        val uuid = UUID.fromString(id)
        return CompletableFuture.completedFuture(ProductVariant(uuid))
    }
}
