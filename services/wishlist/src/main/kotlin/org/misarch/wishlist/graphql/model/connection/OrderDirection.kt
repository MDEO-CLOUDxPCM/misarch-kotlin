package org.misarch.wishlist.graphql.model.connection

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import org.misarch.common.graphql.connection.BaseOrderDirection

/**
 * Direction of an ordering ([sql]: the SQL keyword)
 */
@GraphQLDescription("GraphQL order direction.")
enum class OrderDirection(override val sql: String) : BaseOrderDirection {
    @GraphQLDescription("Ascending order direction.")
    ASC("ASC"),

    @GraphQLDescription("Descending order direction.")
    DESC("DESC"),
}
