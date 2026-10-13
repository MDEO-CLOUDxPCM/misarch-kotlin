package org.misarch.user.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.ShareableDirective

@ShareableDirective
@GraphQLDescription("A name consisting of a firstName and lastName.")
class Name(
    @property:GraphQLDescription("The first name of the user")
    val firstName: String,
    @property:GraphQLDescription("The last name of the user")
    val lastName: String,
)
