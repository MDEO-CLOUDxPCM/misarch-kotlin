package org.misarch.payment.graphql.model.connection

import com.expediagroup.graphql.generator.annotations.GraphQLDescription

/**
 * Order direction of the payment subgraph (other descriptions than the common `OrderDirection`)
 */
@GraphQLDescription("The direction to order a list of items by")
enum class OrderDirection {
    @GraphQLDescription("Ascending Order")
    ASC,

    @GraphQLDescription("Decending Order")
    DESC,
}
