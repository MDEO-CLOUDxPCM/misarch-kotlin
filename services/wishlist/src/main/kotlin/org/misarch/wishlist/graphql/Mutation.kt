package org.misarch.wishlist.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.server.operations.Mutation
import graphql.schema.DataFetchingEnvironment
import org.misarch.wishlist.graphql.input.CreateWishlistInput
import org.misarch.wishlist.graphql.input.UpdateWishlistInput
import org.misarch.wishlist.graphql.model.Wishlist
import org.misarch.wishlist.service.WishlistService
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Describes GraphQL wishlist mutations.
 */
@Component
class Mutation(
    private val wishlistService: WishlistService
) : Mutation {

    @GraphQLDescription(
        "Adds a wishlist with a user_id, a list of product_variant_ids and a name.\n\n" +
            "Formats UUIDs as hyphenated lowercase strings."
    )
    suspend fun createWishlist(
        @GraphQLDescription("CreateWishlistInput")
        input: CreateWishlistInput,
        dfe: DataFetchingEnvironment
    ): Wishlist = wishlistService.createWishlist(dfe, input)

    @GraphQLDescription(
        "Updates name and/or product_variant_ids of a specific wishlist referenced with an UUID.\n\n" +
            "Formats UUIDs as hyphenated lowercase strings."
    )
    suspend fun updateWishlist(
        @GraphQLDescription("UpdateWishlistInput")
        input: UpdateWishlistInput,
        dfe: DataFetchingEnvironment
    ): Wishlist = wishlistService.updateWishlist(dfe, input)

    @GraphQLDescription("Deletes wishlist of UUID.")
    suspend fun deleteWishlist(
        @GraphQLDescription("UUID of wishlist to delete.")
        id: UUID,
        dfe: DataFetchingEnvironment
    ): Boolean = wishlistService.deleteWishlist(dfe, id)
}
