package org.misarch.order.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import java.util.UUID

@GraphQLDescription("Describes the method/provider that the shipment uses.")
@KeyDirective(fields = FieldSet("id"), resolvable = false)
class ShipmentMethod(
    @property:GraphQLDescription("UUID of the shipment method.")
    val id: UUID
)
