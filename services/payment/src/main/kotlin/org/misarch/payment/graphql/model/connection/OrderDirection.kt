package org.misarch.payment.graphql.model.connection

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import org.misarch.common.graphql.connection.BaseOrderDirection

/**
 * Direction of an ordering ([sql]: the SQL keyword)
 */
@GraphQLDescription("The direction to order a list of items by")
enum class OrderDirection(override val sql: String) : BaseOrderDirection {
    @GraphQLDescription("Ascending Order")
    ASC("ASC"),

    @GraphQLDescription("Decending Order")
    DESC("DESC"),
}
