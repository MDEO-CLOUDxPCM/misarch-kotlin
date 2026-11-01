package org.misarch.wishlist.graphql.model.connection

import com.expediagroup.graphql.generator.annotations.GraphQLDescription

@GraphQLDescription("GraphQL order direction.")
enum class OrderDirection(internal val sql: String) {
    @GraphQLDescription("Ascending order direction.")
    ASC("ASC"),

    @GraphQLDescription("Descending order direction.")
    DESC("DESC")
}
