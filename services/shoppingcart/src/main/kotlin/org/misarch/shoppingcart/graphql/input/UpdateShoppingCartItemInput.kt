package org.misarch.shoppingcart.graphql.input

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import java.util.UUID

class UpdateShoppingCartItemInput(
    @property:GraphQLDescription("UUID of shoppingcart item to update.")
    val id: UUID,
    @property:GraphQLDescription("Count of shopping cart items in cart.")
    val count: Int
)
