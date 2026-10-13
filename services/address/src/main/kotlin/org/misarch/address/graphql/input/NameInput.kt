package org.misarch.address.graphql.input

import com.expediagroup.graphql.generator.annotations.GraphQLDescription

@GraphQLDescription("A name consisting of a firstName and lastName.")
class NameInput(
    @property:GraphQLDescription("The first name of the user")
    val firstName: String,
    @property:GraphQLDescription("The last name of the user")
    val lastName: String,
)
