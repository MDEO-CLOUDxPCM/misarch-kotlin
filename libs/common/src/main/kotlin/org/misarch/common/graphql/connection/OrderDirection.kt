package org.misarch.common.graphql.connection

import com.expediagroup.graphql.generator.annotations.GraphQLDescription

/**
 * A GraphQL order direction enum whose constants carry the SQL keyword. [OrderDirection] is the common one; a
 * service whose schema describes the directions differently declares its own enum `OrderDirection` implementing
 * this interface.
 */
interface BaseOrderDirection {
    /** `ASC` or `DESC`. */
    val sql: String
}

/**
 * GraphQL order direction.
 */
@GraphQLDescription("Order direction")
enum class OrderDirection(override val sql: String) : BaseOrderDirection {
    @GraphQLDescription("Ascending order")
    ASC("ASC"),

    @GraphQLDescription("Descending order")
    DESC("DESC");
}
