package org.misarch.shipment.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import java.util.UUID

@GraphQLDescription("An address associated with the vendor.")
@KeyDirective(fields = FieldSet("id"), resolvable = false)
class VendorAddress(id: UUID) : Address(id)