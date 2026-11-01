package org.misarch.inventory.graphql.model.connection

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.annotations.GraphQLIgnore
import com.expediagroup.graphql.generator.annotations.GraphQLSkipInputSuffix
import org.misarch.common.graphql.connection.BaseOrderField

@GraphQLDescription("The field to order Product Items by")
enum class ProductItemOrderField(override vararg val columns: String) : BaseOrderField {
    @GraphQLDescription("Order Product Items by their ID")
    ID("id"),
}

/**
 * Order direction of the inventory and payment subgraphs (other descriptions than the common `OrderDirection`)
 */
@GraphQLDescription("The direction to order a list of items by")
enum class OrderDirection {
    @GraphQLDescription("Ascending Order")
    ASC,

    @GraphQLDescription("Decending Order")
    DESC,
}

@GraphQLDescription("Ordering options for product items")
@GraphQLSkipInputSuffix
class ProductItemOrder(
    @property:GraphQLDescription("The field to order by")
    val field: ProductItemOrderField?,
    @property:GraphQLDescription("The direction to order by")
    val direction: OrderDirection?
) {

    /** SQL order items; field and direction default to ID and ASC */
    @GraphQLIgnore
    fun toOrderBy(): List<String> {
        val direction = direction ?: OrderDirection.ASC
        return (field ?: ProductItemOrderField.ID).columns.map { "$it ${direction.name}" }
    }

    companion object {
        val DEFAULT = ProductItemOrder(ProductItemOrderField.ID, OrderDirection.ASC)
    }
}
