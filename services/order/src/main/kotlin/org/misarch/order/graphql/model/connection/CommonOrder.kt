package org.misarch.order.graphql.model.connection

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import org.misarch.common.graphql.connection.BaseOrderField
import org.misarch.common.graphql.connection.Ordering

/**
 * Order of connections whose entities can only be ordered by id.
 */
@GraphQLDescription("Specifies the order of foreign types.")
class CommonOrder(
    @property:GraphQLDescription("Order direction of orders.")
    override val direction: OrderDirection? = null,
    @property:GraphQLDescription("Field that orders should be ordered by.")
    override val field: CommonOrderField? = null
) : Ordering<CommonOrderField> {

    companion object {
        val DEFAULT = CommonOrder(OrderDirection.ASC, CommonOrderField.ID)
    }
}

@GraphQLDescription("Describes the fields that a foreign types can be ordered by.\n\nOnly the id valid at the moment.")
enum class CommonOrderField(override vararg val columns: String) : BaseOrderField {
    @GraphQLDescription("Orders by \"id\".")
    ID("id")
}
