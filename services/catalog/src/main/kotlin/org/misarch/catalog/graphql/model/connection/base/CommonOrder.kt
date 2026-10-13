package org.misarch.catalog.graphql.model.connection.base

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import org.misarch.common.graphql.connection.OrderDirection

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
     * Convert this order to SQL order items (the field is ignored, there is only `ID`)
     *
     * @param column The column to order by (should be id)
     */
    fun toOrderBy(column: String): List<String> {
        return listOf("$column ${(direction ?: OrderDirection.ASC).name}")
    }

}
