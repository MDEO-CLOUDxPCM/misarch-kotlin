package org.misarch.wishlist.graphql.federation

import graphql.schema.DataFetchingEnvironment
import org.misarch.common.graphql.DataLoaderEntityResolver
import org.misarch.common.graphql.authorizedUser
import org.misarch.wishlist.graphql.dataloader.WishlistDataLoader
import org.misarch.wishlist.graphql.model.Wishlist
import org.springframework.stereotype.Component
import java.util.concurrent.CompletableFuture

/**
 * Federated resolver for [Wishlist]s; only the owner, employees and admins may read a wishlist
 */
@Component
class WishlistResolver : DataLoaderEntityResolver<Wishlist>(Wishlist::class, WishlistDataLoader::class) {

    override fun resolve(environment: DataFetchingEnvironment, representation: Map<String, Any>): CompletableFuture<Wishlist?> {
        return super.resolve(environment, representation).thenApply { wishlist ->
            wishlist?.also { it.checkCanRead(environment) }
        }
    }

}

/**
 * Throws unless the authorized user owns the wishlist or is an employee or admin
 */
internal fun Wishlist.checkCanRead(dfe: DataFetchingEnvironment) {
    val authorizedUser = dfe.authorizedUser
    if (user.id != authorizedUser.id) {
        authorizedUser.checkIsEmployee()
    }
}
