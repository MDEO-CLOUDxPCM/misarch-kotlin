package org.misarch.shoppingcart.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.server.operations.Mutation
import graphql.schema.DataFetchingEnvironment
import org.misarch.shoppingcart.graphql.input.CreateShoppingCartItemInput
import org.misarch.shoppingcart.graphql.input.UpdateShoppingCartInput
import org.misarch.shoppingcart.graphql.input.UpdateShoppingCartItemInput
import org.misarch.shoppingcart.graphql.model.ShoppingCart
import org.misarch.shoppingcart.graphql.model.ShoppingCartItem
import org.misarch.shoppingcart.service.ShoppingcartService
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Describes GraphQL shopping cart mutations.
 */
@Component
class Mutation(
    private val shoppingcartService: ShoppingcartService
) : Mutation {

    @GraphQLDescription(
        "Updates shopping cart items of a specific shopping cart referenced with a UUID.\n\n" +
            "Formats UUIDs as hyphenated lowercase strings."
    )
    suspend fun updateShoppingcart(
        @GraphQLDescription("UpdateShoppingCartInput")
        input: UpdateShoppingCartInput,
        dfe: DataFetchingEnvironment
    ): ShoppingCart = shoppingcartService.updateShoppingcart(dfe, input)

    @GraphQLDescription(
        "Adds shopping cart item to a shopping cart.\n\nQueries for existing item, otherwise adds new shoppingcart item."
    )
    suspend fun createShoppingcartItem(
        @GraphQLDescription("CreateShoppingCartItemInput")
        input: CreateShoppingCartItemInput,
        dfe: DataFetchingEnvironment
    ): ShoppingCartItem = shoppingcartService.createShoppingcartItem(dfe, input)

    @GraphQLDescription("Updates a single shopping cart item.")
    suspend fun updateShoppingcartItem(
        @GraphQLDescription("UpdateShoppingCartItemInput")
        input: UpdateShoppingCartItemInput,
        dfe: DataFetchingEnvironment
    ): ShoppingCartItem = shoppingcartService.updateShoppingcartItem(dfe, input)

    @GraphQLDescription("Deletes shoppingcart item of UUID.")
    suspend fun deleteShoppingcartItem(
        @GraphQLDescription("UUID of shoppingcart item to delete.")
        id: UUID,
        dfe: DataFetchingEnvironment
    ): Boolean = shoppingcartService.deleteShoppingcartItem(dfe, id)
}
