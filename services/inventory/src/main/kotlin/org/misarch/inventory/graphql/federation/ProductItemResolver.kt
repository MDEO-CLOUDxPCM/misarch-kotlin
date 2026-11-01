package org.misarch.inventory.graphql.federation

import graphql.schema.DataFetchingEnvironment
import org.misarch.common.graphql.DataLoaderEntityResolver
import org.misarch.common.graphql.authorizedUser
import org.misarch.inventory.graphql.dataloader.ProductItemDataLoader
import org.misarch.inventory.graphql.model.ProductItem
import org.springframework.stereotype.Component
import java.util.concurrent.CompletableFuture

/**
 * Federated resolver for [ProductItem]s (employees only, like `Query.productItem`).
 */
@Component
class ProductItemResolver : DataLoaderEntityResolver<ProductItem>(ProductItem::class, ProductItemDataLoader::class) {

    override fun resolve(
        environment: DataFetchingEnvironment, representation: Map<String, Any>
    ): CompletableFuture<ProductItem?> {
        try {
            environment.authorizedUser.checkIsEmployee()
        } catch (e: IllegalStateException) {
            return CompletableFuture.failedFuture(e)
        }
        return super.resolve(environment, representation)
    }

}
