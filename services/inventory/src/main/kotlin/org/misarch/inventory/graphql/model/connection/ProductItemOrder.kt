package org.misarch.inventory.graphql.model.connection

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.annotations.GraphQLSkipInputSuffix
import org.misarch.common.graphql.connection.BaseOrderField
import org.misarch.common.graphql.connection.Ordering

@GraphQLDescription("The field to order Product Items by")
enum class ProductItemOrderField(override vararg val columns: String) : BaseOrderField {
    @GraphQLDescription("Order Product Items by their ID")
    ID("id"),
}

@GraphQLDescription("Ordering options for product items")
@GraphQLSkipInputSuffix
class ProductItemOrder(
    @property:GraphQLDescription("The direction to order by")
    override val direction: OrderDirection?,
    @property:GraphQLDescription("The field to order by")
    override val field: ProductItemOrderField?
) : Ordering<ProductItemOrderField> {

    companion object {
        val DEFAULT = ProductItemOrder(OrderDirection.ASC, ProductItemOrderField.ID)
    }
}
