package org.misarch.user.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription

@GraphQLDescription("The gender of a user")
enum class Gender {
    @GraphQLDescription("Male gender")
    MALE,
    @GraphQLDescription("Female gender")
    FEMALE,
    @GraphQLDescription("Diverse gender")
    DIVERSE,
    @GraphQLDescription("Other gender")
    OTHER
}
