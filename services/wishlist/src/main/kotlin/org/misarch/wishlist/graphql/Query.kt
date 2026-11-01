package org.misarch.wishlist.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.server.operations.Query
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.graphql.load
import org.misarch.wishlist.graphql.dataloader.WishlistDataLoader
import org.misarch.wishlist.graphql.federation.checkCanRead
import org.misarch.wishlist.graphql.model.Wishlist
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Defines GraphQL queries
 */
@Component
class Query : Query {

    @GraphQLDescription("Retrieves wishlist of specific UUID.")
    fun wishlist(
        @GraphQLDescription("UUID of wishlist to retrieve.")
        id: UUID,
        dfe: DataFetchingEnvironment
    ): CompletableFuture<Wishlist> {
        return dfe.load(WishlistDataLoader::class, id).thenApply {
            it.checkCanRead(dfe)
            it
        }
    }

}
