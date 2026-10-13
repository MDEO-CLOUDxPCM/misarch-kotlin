package org.misarch.wishlist.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.ShareableDirective

@GraphQLDescription("A connection of product variants.")
@ShareableDirective
class ProductVariantConnection(
    @property:GraphQLDescription("The resulting entities.")
    val nodes: List<ProductVariant>,
    @property:GraphQLDescription("Whether this connection has a next page.")
    val hasNextPage: Boolean,
    @property:GraphQLDescription("The total amount of items in this connection.")
    val totalCount: Int
)

@GraphQLDescription("A connection of wishlists.")
@ShareableDirective
class WishlistConnection(
    @property:GraphQLDescription("The resulting entities.")
    val nodes: List<Wishlist>,
    @property:GraphQLDescription("Whether this connection has a next page.")
    val hasNextPage: Boolean,
    @property:GraphQLDescription("The total amount of items in this connection.")
    val totalCount: Int
)
