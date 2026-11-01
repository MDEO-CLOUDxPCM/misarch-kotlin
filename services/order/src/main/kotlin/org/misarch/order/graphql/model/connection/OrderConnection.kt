package org.misarch.order.graphql.model.connection

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.ShareableDirective
import org.misarch.common.graphql.connection.BaseConnection
import org.misarch.common.graphql.connection.BaseOrderField
import org.misarch.common.graphql.connection.Ordering
import org.misarch.common.graphql.connection.PagingValidation
import org.misarch.common.persistence.Condition
import org.misarch.order.graphql.model.Order
import org.misarch.order.persistence.model.OrderEntity
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate

/**
 * A GraphQL connection for [Order]s.
 *
 * @param first The maximum number of items to return
 * @param skip The number of items to skip
 * @param predicate The predicate to filter the items by
 * @param order The order to sort the items by
 * @param entityTemplate executes the queries
 */
@GraphQLDescription("A connection of orders.")
@ShareableDirective
class OrderConnection(
    first: Int?,
    skip: Int?,
    predicate: Condition?,
    order: OrderOrder?,
    entityTemplate: R2dbcEntityTemplate
) : BaseConnection<Order, OrderEntity>(
    first,
    skip,
    null,
    predicate,
    (order ?: OrderOrder.DEFAULT).toOrderBy(OrderOrderField.ID, TIE_BREAK),
    entityTemplate,
    OrderEntity::class,
    null
) {

    override val pagingValidation: PagingValidation get() = PagingValidation.NON_NEGATIVE

    @GraphQLDescription("The resulting entities.")
    override suspend fun nodes(): List<Order> = super.nodes()

    @GraphQLDescription("Whether this connection has a next page.")
    override suspend fun hasNextPage(): Boolean = super.hasNextPage()

    @GraphQLDescription("The total amount of items in this connection.")
    override suspend fun totalCount(): Int = super.totalCount()

    companion object {
        /** Orders with the same value of the order field are ordered by descending id. */
        val TIE_BREAK = listOf("id DESC")
    }
}

/**
 * Order of orders.
 */
@GraphQLDescription("Specifies the order of orders.")
class OrderOrder(
    @property:GraphQLDescription("Order direction of orders.")
    override val direction: OrderDirection? = null,
    @property:GraphQLDescription("Field that orders should be ordered by.")
    override val field: OrderOrderField? = null
) : Ordering<OrderOrderField> {

    companion object {
        val DEFAULT = OrderOrder(OrderDirection.ASC, OrderOrderField.ID)
    }
}

/**
 * The fields orders can be ordered by. Orders have no name and no update time: `NAME` and `LAST_UPDATED_AT` (part of
 * the schema) order by the tie-break only.
 */
@GraphQLDescription("Describes the fields that a order can be ordered by.")
enum class OrderOrderField(override vararg val columns: String) : BaseOrderField {
    @GraphQLDescription("Orders by \"id\".")
    ID("id"),

    @GraphQLDescription("Orders by \"user_id\".")
    USER_ID("userId"),

    @GraphQLDescription("Orders by \"name\".")
    NAME,

    @GraphQLDescription("Orders by \"created_at\".")
    CREATED_AT("createdAt"),

    @GraphQLDescription("Orders by \"last_updated_at\".")
    LAST_UPDATED_AT
}
