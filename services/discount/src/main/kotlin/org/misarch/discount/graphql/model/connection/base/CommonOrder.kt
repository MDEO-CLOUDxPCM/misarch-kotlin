package org.misarch.discount.graphql.model.connection.base

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import org.misarch.common.graphql.connection.BaseOrder
import org.misarch.common.graphql.connection.OrderDirection

/**
 * Order of connections of entities that can only be ordered by id (categories, products, product variants, users).
 * The original's GraphQL description of this input is "Discount order" (copy-paste), kept for an identical SDL.
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
