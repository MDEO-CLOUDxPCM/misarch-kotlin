package org.misarch.wishlist.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.server.operations.Query
import graphql.schema.DataFetchingEnvironment
import org.misarch.wishlist.graphql.model.Wishlist
import org.misarch.wishlist.service.WishlistService
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Describes GraphQL wishlist queries (entity resolvers: graphql/federation).
 */
@Component
class Query(
    private val wishlistService: WishlistService
) : Query {

    @GraphQLDescription("Retrieves wishlist of specific UUID.")
    suspend fun wishlist(
        @GraphQLDescription("UUID of wishlist to retrieve.")
        id: UUID,
        dfe: DataFetchingEnvironment
    ): Wishlist = wishlistService.wishlist(dfe, id)
}
