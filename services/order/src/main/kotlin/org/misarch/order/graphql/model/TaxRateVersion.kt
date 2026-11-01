package org.misarch.order.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import java.util.UUID

@GraphQLDescription("Foreign type of a tax rate version.")
@KeyDirective(fields = FieldSet("id"), resolvable = false)
class TaxRateVersion(
    @property:GraphQLDescription("UUID of the tax rate.")
    val id: UUID
)
