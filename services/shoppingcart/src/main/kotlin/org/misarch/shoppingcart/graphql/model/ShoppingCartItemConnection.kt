package org.misarch.shoppingcart.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.ShareableDirective

@GraphQLDescription("A connection of shopping cart items.")
@ShareableDirective
class ShoppingCartItemConnection(
    @property:GraphQLDescription("The resulting entities.")
    val nodes: List<ShoppingCartItem>,
    @property:GraphQLDescription("Whether this connection has a next page.")
    val hasNextPage: Boolean,
    @property:GraphQLDescription("The total amount of items in this connection.")
    val totalCount: Int
)
