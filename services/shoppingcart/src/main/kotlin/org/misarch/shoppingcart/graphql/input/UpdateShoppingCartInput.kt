package org.misarch.shoppingcart.graphql.input

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import java.util.UUID

class UpdateShoppingCartInput(
    @property:GraphQLDescription("UUID of user owning shopping cart.")
    val id: UUID,
    @property:GraphQLDescription("Shopping cart items of shopping cart to update.")
    val shoppingCartItems: List<ShoppingCartItemInput>?
)
