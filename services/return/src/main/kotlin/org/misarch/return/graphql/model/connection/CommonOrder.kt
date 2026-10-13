package org.misarch.`return`.graphql.model.connection

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import org.misarch.common.graphql.connection.OrderDirection

/**
 * Order input whose field is ignored: always orders by the given column (id). The description "Discount order"
 * is a copy-paste leftover of the original and part of the SDL.
 */
@GraphQLDescription("Discount order")
class CommonOrder(
    @GraphQLDescription("The direction to order by")
    val direction: OrderDirection?,
    @GraphQLDescription("The field to order by")
    val field: CommonOrderField?
) {
    companion object {
        val DEFAULT = CommonOrder(OrderDirection.ASC, CommonOrderField.ID)
    }

    /**
     * Convert this order to SQL order items
     *
     * @param column The column to order by (should be id)
     */
    fun toOrderBy(column: String): List<String> {
        return listOf("$column ${(direction ?: OrderDirection.ASC).name}")
    }

}

@GraphQLDescription("Common order fields")
enum class CommonOrderField {
    @GraphQLDescription("Order entities by their id")
    ID,
}
