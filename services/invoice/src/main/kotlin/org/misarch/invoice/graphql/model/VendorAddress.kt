package org.misarch.invoice.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import java.util.UUID

@GraphQLDescription("Foreign type of a vendor address.")
@KeyDirective(fields = FieldSet("id"), resolvable = false)
class VendorAddress(
    val id: UUID
)
