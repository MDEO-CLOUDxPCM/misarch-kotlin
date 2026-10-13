package org.misarch.address.graphql.input

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import java.util.UUID

@GraphQLDescription("Input for the createUserAddress mutation.")
class CreateUserAddressInput(
    name: NameInput?,
    street1: String,
    street2: String,
    city: String,
    postalCode: String,
    country: String,
    companyName: String?,
    @property:GraphQLDescription("The id of the user to create the address for.")
    val userId: UUID,
) : CreateAddressInput(name, street1, street2, city, postalCode, country, companyName)
