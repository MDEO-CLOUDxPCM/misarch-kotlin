package org.misarch.wishlist.graphql.input

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import java.util.UUID

class CreateWishlistInput(
    @property:GraphQLDescription("UUID of user owning the wishlist.")
    val userId: UUID,
    @property:GraphQLDescription("UUIDs of product variants in wishlist.")
    val productVariantIds: List<UUID>,
    @property:GraphQLDescription("Wishlist name.")
    val name: String
)

class UpdateWishlistInput(
    @property:GraphQLDescription("UUID of wishlist to update.")
    val id: UUID,
    @property:GraphQLDescription("product variant UUIDs of wishlist to update")
    val productVariantIds: List<UUID>? = null,
    @property:GraphQLDescription("Wishlist name to update")
    val name: String? = null
)
