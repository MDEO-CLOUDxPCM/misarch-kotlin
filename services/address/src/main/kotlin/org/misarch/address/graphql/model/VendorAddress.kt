package org.misarch.address.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import java.util.UUID

@GraphQLDescription("An address associated with the vendor.")
@KeyDirective(fields = FieldSet("id"))
class VendorAddress(
    id: UUID,
    name: Name?,
    street1: String,
    street2: String,
    city: String,
    postalCode: String,
    country: String,
    companyName: String?
) : Address(id, name, street1, street2, city, postalCode, country, companyName)
