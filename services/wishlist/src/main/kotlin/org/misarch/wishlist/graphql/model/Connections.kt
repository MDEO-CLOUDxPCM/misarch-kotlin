package org.misarch.wishlist.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.ShareableDirective
import org.misarch.common.graphql.GraphQLErrorException
import org.misarch.common.graphql.connection.ConnectionQuery
import org.misarch.wishlist.persistence.WishlistRow
import org.misarch.wishlist.service.Messages
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/*
 * The connections of the original (async-graphql `nodes`/`hasNextPage`/`totalCount`); each field is one statement of
 * the [ConnectionQuery] (page / probe for a further row / count), run only when selected.
 */

@GraphQLDescription("A connection of product variants.")
@ShareableDirective
class ProductVariantConnection(private val query: ConnectionQuery<UUID>) {
    @GraphQLDescription("The resulting entities.")
    suspend fun nodes(): List<ProductVariant> = query.nodes().map { ProductVariant(it) }

    @GraphQLDescription("Whether this connection has a next page.")
    suspend fun hasNextPage(): Boolean = query.hasNextPage()

    @GraphQLDescription("The total amount of items in this connection.")
    suspend fun totalCount(): Int = query.totalCount()
}

/** A database failure of any of the statements is the original's `Retrieving wishlists failed in MongoDB.` */
@GraphQLDescription("A connection of wishlists.")
@ShareableDirective
class WishlistConnection(private val query: ConnectionQuery<WishlistRow>) {
    @GraphQLDescription("The resulting entities.")
    suspend fun nodes(): List<Wishlist> = retrieve { query.nodes().map { Wishlist.of(it) } }

    @GraphQLDescription("Whether this connection has a next page.")
    suspend fun hasNextPage(): Boolean = retrieve { query.hasNextPage() }

    @GraphQLDescription("The total amount of items in this connection.")
    suspend fun totalCount(): Int = retrieve { query.totalCount() }

    private suspend fun <T> retrieve(block: suspend () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw GraphQLErrorException(Messages.RETRIEVE_WISHLISTS_FAILED, cause = e)
        }
}
