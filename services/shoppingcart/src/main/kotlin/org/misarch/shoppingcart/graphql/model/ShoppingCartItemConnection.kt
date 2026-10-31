package org.misarch.shoppingcart.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.ShareableDirective
import org.misarch.common.graphql.connection.ConnectionQuery
import org.misarch.shoppingcart.persistence.ShoppingCartItemRow

/**
 * The connection of the original (async-graphql `nodes`/`hasNextPage`/`totalCount`); each field is one statement of
 * the [ConnectionQuery] (page / probe for a further row / count), run only when selected.
 */
@GraphQLDescription("A connection of shopping cart items.")
@ShareableDirective
class ShoppingCartItemConnection(private val query: ConnectionQuery<ShoppingCartItemRow>) {
    @GraphQLDescription("The resulting entities.")
    suspend fun nodes(): List<ShoppingCartItem> = query.nodes().map { ShoppingCartItem.of(it) }

    @GraphQLDescription("Whether this connection has a next page.")
    suspend fun hasNextPage(): Boolean = query.hasNextPage()

    @GraphQLDescription("The total amount of items in this connection.")
    suspend fun totalCount(): Int = query.totalCount()
}
