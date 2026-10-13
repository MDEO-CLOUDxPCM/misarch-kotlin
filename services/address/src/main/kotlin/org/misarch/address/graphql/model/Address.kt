package org.misarch.address.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import org.misarch.common.graphql.Node
import java.util.UUID

@GraphQLDescription("An address.")
abstract class Address(
    id: UUID,
    @property:GraphQLDescription("The name of the address")
    val name: Name?,
    @property:GraphQLDescription("The first part of the street part of the address")
    val street1: String,
    @property:GraphQLDescription("The second part of the street part of the address")
    val street2: String,
    @property:GraphQLDescription("The city part of the address")
    val city: String,
    @property:GraphQLDescription("The postal code part of the address")
    val postalCode: String,
    @property:GraphQLDescription("The country part of the address")
    val country: String,
    @property:GraphQLDescription("The company name part of the address")
    val companyName: String?
) : Node(id)
