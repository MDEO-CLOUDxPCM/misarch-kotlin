package org.misarch.catalog.graphql.model.connection

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.ShareableDirective
import org.misarch.catalog.graphql.model.Category
import org.misarch.catalog.persistence.model.CategoryEntity
import org.misarch.common.graphql.AuthorizedUser
import org.misarch.common.graphql.connection.BaseConnection
import org.misarch.common.graphql.connection.BaseOrder
import org.misarch.common.graphql.connection.BaseOrderField
import org.misarch.common.graphql.connection.OrderDirection
import org.misarch.common.persistence.Condition
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate

/**
 * A GraphQL connection for [Category]s.
 *
 * @param first The maximum number of items to return
 * @param skip The number of items to skip
 * @param predicate The predicate to filter the items by
 * @param order The order to sort the items by
 * @param entityTemplate executes the queries
 * @param join An optional join clause (main table alias `t`)
 */
@GraphQLDescription("A connection to a list of `Category` values.")
@ShareableDirective
class CategoryConnection(
    first: Int?,
    skip: Int?,
    predicate: Condition?,
    order: CategoryOrder?,
    entityTemplate: R2dbcEntityTemplate,
    authorizedUser: AuthorizedUser?,
    join: String? = null
) : BaseConnection<Category, CategoryEntity>(
    first,
    skip,
    null,
    predicate,
    (order ?: CategoryOrder.DEFAULT).toOrderBy(CategoryOrderField.ID),
    entityTemplate,
    CategoryEntity::class,
    authorizedUser,
    join
) {

}

@GraphQLDescription("Category order fields")
enum class CategoryOrderField(override vararg val columns: String) : BaseOrderField {
    @GraphQLDescription("Order categories by their id")
    ID("id"),

    @GraphQLDescription("Order categories by their name")
    NAME("name", "id")
}

@GraphQLDescription("Category order")
class CategoryOrder(
    direction: OrderDirection?, field: CategoryOrderField?
) : BaseOrder<CategoryOrderField>(direction, field) {

    companion object {
        val DEFAULT = CategoryOrder(OrderDirection.ASC, CategoryOrderField.ID)
    }
}
