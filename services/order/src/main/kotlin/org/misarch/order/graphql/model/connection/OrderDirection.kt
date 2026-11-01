package org.misarch.order.graphql.model.connection

import com.expediagroup.graphql.generator.annotations.GraphQLDescription

@GraphQLDescription("GraphQL order direction.")
enum class OrderDirection {
    @GraphQLDescription("Ascending order direction.")
    ASC,

    @GraphQLDescription("Descending order direction.")
    DESC
}
