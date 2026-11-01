package org.misarch.shoppingcart.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.server.operations.Mutation
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.graphql.authorizedUser
import org.misarch.shoppingcart.graphql.input.CreateShoppingCartItemInput
import org.misarch.shoppingcart.graphql.input.UpdateShoppingCartInput
import org.misarch.shoppingcart.graphql.input.UpdateShoppingCartItemInput
import org.misarch.shoppingcart.graphql.model.ShoppingCart
import org.misarch.shoppingcart.graphql.model.ShoppingCartItem
import org.misarch.shoppingcart.service.ShoppingCartItemService
import org.misarch.shoppingcart.service.ShoppingCartService
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Defines GraphQL mutations
 */
@Component
class Mutation(
    private val shoppingCartService: ShoppingCartService,
    private val shoppingCartItemService: ShoppingCartItemService,
) : Mutation {

    @GraphQLDescription(
        "Updates shopping cart items of a specific shopping cart referenced with a UUID.\n\n" +
            "Formats UUIDs as hyphenated lowercase strings."
    )
    suspend fun updateShoppingcart(
        @GraphQLDescription("UpdateShoppingCartInput")
        input: UpdateShoppingCartInput,
        dfe: DataFetchingEnvironment
    ): ShoppingCart {
        val authorizedUser = dfe.authorizedUser
        if (input.id != authorizedUser.id) {
            authorizedUser.checkIsEmployee()
        }
        return shoppingCartService.updateShoppingCart(input).toDTO().shoppingcart
    }

    @GraphQLDescription(
        "Adds shopping cart item to a shopping cart.\n\nQueries for existing item, otherwise adds new shoppingcart item."
    )
    suspend fun createShoppingcartItem(
        @GraphQLDescription("CreateShoppingCartItemInput")
        input: CreateShoppingCartItemInput,
        dfe: DataFetchingEnvironment
    ): ShoppingCartItem {
        val authorizedUser = dfe.authorizedUser
        if (input.id != authorizedUser.id) {
            authorizedUser.checkIsEmployee()
        }
        return shoppingCartItemService.createShoppingCartItem(input).toDTO()
    }

    @GraphQLDescription("Updates a single shopping cart item.")
    suspend fun updateShoppingcartItem(
        @GraphQLDescription("UpdateShoppingCartItemInput")
        input: UpdateShoppingCartItemInput,
        dfe: DataFetchingEnvironment
    ): ShoppingCartItem {
        return shoppingCartItemService.updateShoppingCartItem(input, dfe.authorizedUser).toDTO()
    }

    @GraphQLDescription("Deletes shoppingcart item of UUID.")
    suspend fun deleteShoppingcartItem(
        @GraphQLDescription("UUID of shoppingcart item to delete.")
        id: UUID,
        dfe: DataFetchingEnvironment
    ): Boolean {
        shoppingCartItemService.deleteShoppingCartItem(id, dfe.authorizedUser)
        return true
    }

}
