package org.misarch.discount.graphql.model.connection

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.ShareableDirective
import org.misarch.common.graphql.AuthorizedUser
import org.misarch.common.graphql.connection.BaseConnection
import org.misarch.common.graphql.connection.CommonOrder
import org.misarch.common.graphql.connection.CommonOrderField
import org.misarch.common.persistence.Condition
import org.misarch.discount.graphql.model.Category
import org.misarch.discount.persistence.model.CategoryEntity
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate

/**
 * A GraphQL connection for [Category]s.
 *
 * @param first The maximum number of items to return
 * @param skip The number of items to skip
 * @param predicate The predicate to filter the items by
 * @param order The order to sort the items by
 * @param entityTemplate executes the queries
 * @param authorizedUser The authorized user
 * @param join An optional join clause (alias `j`, main table alias `t`)
 */
@GraphQLDescription("A connection to a list of `Category` values.")
@ShareableDirective
class CategoryConnection(
    first: Int?,
    skip: Int?,
    predicate: Condition?,
    order: CommonOrder?,
    entityTemplate: R2dbcEntityTemplate,
    authorizedUser: AuthorizedUser?,
    join: String? = null
) : BaseConnection<Category, CategoryEntity>(
    first,
    skip,
    null,
    predicate,
    (order ?: CommonOrder.DEFAULT).toOrderBy(CommonOrderField.ID),
    entityTemplate,
    CategoryEntity::class,
    authorizedUser,
    join
) {

}
