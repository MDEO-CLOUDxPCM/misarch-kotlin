package org.misarch.order.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import java.util.UUID

@GraphQLDescription("Foreign type of a product variant.")
@KeyDirective(fields = FieldSet("id"), resolvable = false)
class ProductVariantVersion(
    @property:GraphQLDescription("UUID of the product variant version.")
    val id: UUID
)
