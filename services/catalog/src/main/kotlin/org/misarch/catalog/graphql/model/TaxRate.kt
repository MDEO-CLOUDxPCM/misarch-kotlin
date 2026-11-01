package org.misarch.catalog.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import java.util.UUID

@GraphQLDescription("A TaxRate.")
@KeyDirective(fields = FieldSet("id"), resolvable = false)
class TaxRate(
    id: UUID
) : Node(id)
