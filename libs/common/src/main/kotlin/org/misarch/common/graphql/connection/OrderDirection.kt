package org.misarch.common.graphql.connection

import com.expediagroup.graphql.generator.annotations.GraphQLDescription

/**
 * GraphQL order direction (identical in all MiSArch subgraphs).
 */
@GraphQLDescription("Order direction")
enum class OrderDirection(internal val sql: String) {
    @GraphQLDescription("Ascending order")
    ASC("ASC"),

    @GraphQLDescription("Descending order")
    DESC("DESC");
}
