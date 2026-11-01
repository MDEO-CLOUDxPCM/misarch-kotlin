package org.misarch.`return`.graphql.model.connection

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.ShareableDirective
import org.misarch.common.graphql.AuthorizedUser
import org.misarch.common.graphql.connection.BaseConnection
import org.misarch.common.graphql.connection.CommonOrder
import org.misarch.common.graphql.connection.CommonOrderField
import org.misarch.common.persistence.Condition
import org.misarch.`return`.graphql.model.OrderItem
import org.misarch.`return`.persistence.model.OrderItemEntity
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate

/**
 * A GraphQL connection for [OrderItem]s.
 *
 * @param first The maximum number of items to return
 * @param skip The number of items to skip
 * @param predicate The predicate to filter the items by
 * @param order The order to sort the items by
 * @param entityTemplate executes the queries
 * @param authorizedUser The authorized user
 */
@GraphQLDescription("A connection to a list of `OrderItem` values.")
@ShareableDirective
class OrderItemConnection(
    first: Int?,
    skip: Int?,
    predicate: Condition?,
    order: CommonOrder?,
    entityTemplate: R2dbcEntityTemplate,
    authorizedUser: AuthorizedUser?,
) : BaseConnection<OrderItem, OrderItemEntity>(
    first,
    skip,
    null,
    predicate,
    (order ?: CommonOrder.DEFAULT).toOrderBy(CommonOrderField.ID),
    entityTemplate,
    OrderItemEntity::class,
    authorizedUser,
) {

}
