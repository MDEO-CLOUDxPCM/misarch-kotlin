package org.misarch.common.graphql.connection

import com.expediagroup.graphql.generator.annotations.GraphQLDescription

/**
 * Order of connections whose entities can only be ordered by id (catalog, discount, return). The GraphQL description
 * "Discount order" is a copy-paste leftover of the original discount service that the other originals copied; it is
 * part of their SDL and therefore kept.
 */
@GraphQLDescription("Discount order")
class CommonOrder(
    direction: OrderDirection?,
    field: CommonOrderField?
) : BaseOrder<CommonOrderField>(direction, field) {

    companion object {
        val DEFAULT = CommonOrder(OrderDirection.ASC, CommonOrderField.ID)
    }
}

@GraphQLDescription("Common order fields")
enum class CommonOrderField(override vararg val columns: String) : BaseOrderField {
    @GraphQLDescription("Order entities by their id")
    ID("id"),
}
