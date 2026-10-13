package org.misarch.shipment.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import java.util.*

@GraphQLDescription("A address associated with a user.")
@KeyDirective(fields = FieldSet("id"), resolvable = false)
class UserAddress(id: UUID) : Address(id)