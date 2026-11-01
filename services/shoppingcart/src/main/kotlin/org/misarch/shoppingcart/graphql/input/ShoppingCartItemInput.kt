package org.misarch.shoppingcart.graphql.input

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import java.util.UUID

class ShoppingCartItemInput(
    @property:GraphQLDescription("Count of shopping cart items in cart.")
    val count: Int,
    @property:GraphQLDescription("UUID of product variant.")
    val productVariantId: UUID
)
