package org.misarch.wishlist.graphql.model.connection

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import org.misarch.common.graphql.connection.BaseOrderField
import org.misarch.common.graphql.connection.Ordering

/**
 * Order of connections whose entities can only be ordered by id
 */
@GraphQLDescription("Specifies the order of foreign types.")
class CommonOrder(
    @property:GraphQLDescription("Order direction of wishlists.")
    override val direction: OrderDirection?,
    @property:GraphQLDescription("Field that wishlists should be ordered by.")
    override val field: CommonOrderField?
) : Ordering<CommonOrderField> {

    companion object {
        val DEFAULT = CommonOrder(OrderDirection.ASC, CommonOrderField.ID)
    }
}

@GraphQLDescription("Describes the fields that a foreign types can be ordered by.")
enum class CommonOrderField(override vararg val columns: String) : BaseOrderField {
    @GraphQLDescription("Orders by \"id\".")
    ID("id"),
}
