package org.misarch.catalog.graphql.model.connection

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.ShareableDirective
import org.misarch.catalog.graphql.model.CategoryCharacteristicValue
import org.misarch.catalog.persistence.model.CategoryCharacteristicValueEntity
import org.misarch.common.graphql.AuthorizedUser
import org.misarch.common.graphql.connection.BaseConnection
import org.misarch.common.graphql.connection.BaseOrder
import org.misarch.common.graphql.connection.BaseOrderField
import org.misarch.common.graphql.connection.OrderDirection
import org.misarch.common.persistence.Condition
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate

/**
 * A GraphQL connection for [CategoryCharacteristicValue]s.
 *
 * @param first The maximum number of items to return
 * @param skip The number of items to skip
 * @param predicate The predicate to filter the items by
 * @param order The order to sort the items by
 * @param entityTemplate executes the queries
 * @param authorizedUser The authorized user
 * @param join An optional join clause (main table alias `t`)
 */
@GraphQLDescription("A connection to a list of `CategoryCharacteristicValue` values.")
@ShareableDirective
class CategoryCharacteristicValueConnection(
    first: Int?,
    skip: Int?,
    predicate: Condition?,
    order: CategoryCharacteristicValueOrder?,
    entityTemplate: R2dbcEntityTemplate,
    authorizedUser: AuthorizedUser?,
    join: String? = null
) : BaseConnection<CategoryCharacteristicValue, CategoryCharacteristicValueEntity>(
    first,
    skip,
    null,
    predicate,
    (order ?: CategoryCharacteristicValueOrder.DEFAULT).toOrderBy(CategoryCharacteristicValueOrderField.ID),
    entityTemplate,
    CategoryCharacteristicValueEntity::class,
    authorizedUser,
    join
) {

}

@GraphQLDescription("CategoryCharacteristicValue order fields")
enum class CategoryCharacteristicValueOrderField(
    override vararg val columns: String
) : BaseOrderField {
    @GraphQLDescription("Order categoryCharacteristicValues by their id")
    ID("id")
}

@GraphQLDescription("CategoryCharacteristicValue order")
class CategoryCharacteristicValueOrder(
    direction: OrderDirection?, field: CategoryCharacteristicValueOrderField?
) : BaseOrder<CategoryCharacteristicValueOrderField>(direction, field) {

    companion object {
        val DEFAULT = CategoryCharacteristicValueOrder(OrderDirection.ASC, CategoryCharacteristicValueOrderField.ID)
    }
}
