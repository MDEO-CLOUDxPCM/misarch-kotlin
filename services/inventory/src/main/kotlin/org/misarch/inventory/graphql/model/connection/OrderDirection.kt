package org.misarch.inventory.graphql.model.connection

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import org.misarch.common.graphql.connection.BaseOrderDirection

/**
 * Order direction, with the descriptions of this service's schema ("Decending" is part of it).
 */
@GraphQLDescription("The direction to order a list of items by")
enum class OrderDirection(override val sql: String) : BaseOrderDirection {
    @GraphQLDescription("Ascending Order")
    ASC("ASC"),

    @GraphQLDescription("Decending Order")
    DESC("DESC"),
}
