package org.misarch.shoppingcart.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription

@GraphQLDescription("GraphQL order direction.")
enum class OrderDirection {
    @GraphQLDescription("Ascending order direction.")
    ASC,

    @GraphQLDescription("Descending order direction.")
    DESC
}

@GraphQLDescription("Describes the fields that a foreign types can be ordered by.")
enum class CommonOrderField {
    @GraphQLDescription("Orders by \"id\".")
    ID
}

@GraphQLDescription("Specifies the order of foreign types.")
class CommonOrderInput(
    @property:GraphQLDescription("Order direction of shoppingcarts.")
    val direction: OrderDirection? = null,
    @property:GraphQLDescription("Field that shoppingcarts should be ordered by.")
    val field: CommonOrderField? = null
)
