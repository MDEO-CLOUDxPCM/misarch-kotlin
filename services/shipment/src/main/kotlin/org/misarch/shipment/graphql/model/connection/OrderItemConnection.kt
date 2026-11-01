package org.misarch.shipment.graphql.model.connection

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.ShareableDirective
import org.misarch.common.graphql.AuthorizedUser
import org.misarch.common.graphql.connection.BaseConnection
import org.misarch.common.graphql.connection.BaseOrderField
import org.misarch.common.graphql.connection.Ordering
import org.misarch.common.persistence.Condition
import org.misarch.shipment.graphql.model.OrderItem
import org.misarch.shipment.persistence.model.OrderItemEntity
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
 * @param join optional join clause (main table alias `t`)
 */
@GraphQLDescription("A connection to a list of `OrderItem` values.")
@ShareableDirective
class OrderItemConnection(
    first: Int?,
    skip: Int?,
    predicate: Condition?,
    order: OrderItemOrder?,
    entityTemplate: R2dbcEntityTemplate,
    authorizedUser: AuthorizedUser?,
    join: String? = null
) : BaseConnection<OrderItem, OrderItemEntity>(
    first,
    skip,
    null,
    predicate,
    (order ?: OrderItemOrder.DEFAULT).toOrderBy(OrderItemOrderField.ID),
    entityTemplate,
    OrderItemEntity::class,
    authorizedUser,
    join
) {

}

@GraphQLDescription("OrderItem order fields")
enum class OrderItemOrderField(override vararg val columns: String) : BaseOrderField {
    @GraphQLDescription("Order order items by their id")
    ID("id")
}

@GraphQLDescription("OrderItem order")
class OrderItemOrder(
    @property:GraphQLDescription("The direction to order by")
    override val direction: OrderDirection?,
    @property:GraphQLDescription("The field to order by")
    override val field: OrderItemOrderField?
) : Ordering<OrderItemOrderField> {

    companion object {
        val DEFAULT = OrderItemOrder(OrderDirection.ASC, OrderItemOrderField.ID)
    }
}
