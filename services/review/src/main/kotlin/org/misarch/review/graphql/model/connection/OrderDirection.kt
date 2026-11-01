package org.misarch.review.graphql.model.connection

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import org.misarch.common.graphql.connection.BaseOrderDirection

/**
 * Order direction, with the descriptions of this service's schema.
 */
@GraphQLDescription("GraphQL order direction.")
enum class OrderDirection(override val sql: String) : BaseOrderDirection {
    @GraphQLDescription("Ascending order direction.")
    ASC("ASC"),

    @GraphQLDescription("Descending order direction.")
    DESC("DESC")
}
