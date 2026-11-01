package org.misarch.shoppingcart.graphql.input

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import java.util.UUID

class CreateShoppingCartItemInput(
    @property:GraphQLDescription("UUID of user owning the shopping cart.")
    val id: UUID,
    @property:GraphQLDescription("shopping cart item in shopping cart to update")
    val shoppingCartItem: ShoppingCartItemInput
)
