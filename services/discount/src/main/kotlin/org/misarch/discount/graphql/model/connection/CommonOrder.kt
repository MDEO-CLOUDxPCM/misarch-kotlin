package org.misarch.discount.graphql.model.connection

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import org.misarch.common.graphql.connection.BaseOrderField
import org.misarch.common.graphql.connection.Ordering

/**
 * Order of connections whose entities can only be ordered by id
 */
@GraphQLDescription("Discount order")
class CommonOrder(
    @property:GraphQLDescription("The direction to order by")
    override val direction: OrderDirection?,
    @property:GraphQLDescription("The field to order by")
    override val field: CommonOrderField?
) : Ordering<CommonOrderField> {

    companion object {
        val DEFAULT = CommonOrder(OrderDirection.ASC, CommonOrderField.ID)
    }
}

@GraphQLDescription("Common order fields")
enum class CommonOrderField(override vararg val columns: String) : BaseOrderField {
    @GraphQLDescription("Order entities by their id")
    ID("id"),
}
