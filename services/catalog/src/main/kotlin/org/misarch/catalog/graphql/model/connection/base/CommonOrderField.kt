package org.misarch.catalog.graphql.model.connection.base

import com.expediagroup.graphql.generator.annotations.GraphQLDescription

@GraphQLDescription("Common order fields")
enum class CommonOrderField{
    @GraphQLDescription("Order entities by their id")
    ID,
}
