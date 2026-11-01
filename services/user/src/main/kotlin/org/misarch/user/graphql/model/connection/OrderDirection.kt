package org.misarch.user.graphql.model.connection

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import org.misarch.common.graphql.connection.BaseOrderDirection

/**
 * Direction of an ordering ([sql]: the SQL keyword)
 */
@GraphQLDescription("Order direction")
enum class OrderDirection(override val sql: String) : BaseOrderDirection {
    @GraphQLDescription("Ascending order")
    ASC("ASC"),

    @GraphQLDescription("Descending order")
    DESC("DESC"),
}
