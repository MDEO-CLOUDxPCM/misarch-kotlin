package org.misarch.shoppingcart.graphql.input

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import java.util.UUID

class UpdateShoppingCartInput(
    @property:GraphQLDescription("UUID of user owning shopping cart.")
    val id: UUID,
    @property:GraphQLDescription("Shopping cart items of shopping cart to update.")
    val shoppingCartItems: List<ShoppingCartItemInput>? = null
)

class ShoppingCartItemInput(
    @property:GraphQLDescription("Count of shopping cart items in cart.")
    val count: Int,
    @property:GraphQLDescription("UUID of product variant.")
    val productVariantId: UUID
)

class CreateShoppingCartItemInput(
    @property:GraphQLDescription("UUID of user owning the shopping cart.")
    val id: UUID,
    @property:GraphQLDescription("shopping cart item in shopping cart to update")
    val shoppingCartItem: ShoppingCartItemInput
)

class UpdateShoppingCartItemInput(
    @property:GraphQLDescription("UUID of shoppingcart item to update.")
    val id: UUID,
    @property:GraphQLDescription("Count of shopping cart items in cart.")
    val count: Int
)
