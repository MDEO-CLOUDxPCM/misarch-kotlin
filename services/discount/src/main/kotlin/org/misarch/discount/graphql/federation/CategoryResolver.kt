package org.misarch.discount.graphql.federation

import com.expediagroup.graphql.generator.federation.execution.FederatedTypePromiseResolver
import graphql.schema.DataFetchingEnvironment
import org.misarch.discount.graphql.model.Category
import org.springframework.stereotype.Component
import java.util.*
import java.util.concurrent.CompletableFuture

/**
 * Federated resolver for [Category]s (no database access: the reference only carries the id).
 */
@Component
class CategoryResolver : FederatedTypePromiseResolver<Category> {
    override val typeName: String
        get() = Category::class.simpleName!!

    override fun resolve(
        environment: DataFetchingEnvironment, representation: Map<String, Any>
    ): CompletableFuture<Category?> {
        val id = representation["id"] as String?
        val uuid = UUID.fromString(id)
        return CompletableFuture.completedFuture(Category(uuid))
    }
}
