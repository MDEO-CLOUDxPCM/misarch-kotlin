package org.misarch.shoppingcart.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import java.util.UUID

@GraphQLDescription("Type of a user owning shoppingcarts.")
@KeyDirective(fields = FieldSet("id"))
class User(
    @property:GraphQLDescription("UUID of the user.")
    val id: UUID,
    @property:GraphQLDescription("Shopping cart of the user.")
    val shoppingcart: ShoppingCart
)
