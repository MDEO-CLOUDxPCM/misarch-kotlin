package org.misarch.order.graphql.model.connection

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.ShareableDirective
import org.misarch.common.graphql.connection.BaseConnection
import org.misarch.common.graphql.connection.PagingValidation
import org.misarch.common.persistence.Condition
import org.misarch.order.graphql.model.Discount
import org.misarch.order.persistence.model.OrderItemToDiscountEntity
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate

/**
 * A GraphQL connection for the [Discount]s of an order item.
 *
 * @param first The maximum number of items to return
 * @param skip The number of items to skip
 * @param predicate The predicate to filter the items by
 * @param order The order to sort the items by
 * @param entityTemplate executes the queries
 */
@GraphQLDescription("A connection of discounts.")
@ShareableDirective
class DiscountConnection(
    first: Int?,
    skip: Int?,
    predicate: Condition?,
    order: CommonOrderInput?,
    entityTemplate: R2dbcEntityTemplate
) : BaseConnection<Discount, OrderItemToDiscountEntity>(
    first,
    skip,
    null,
    predicate,
    (order ?: CommonOrderInput()).toOrderBy("discountId"),
    entityTemplate,
    OrderItemToDiscountEntity::class,
    null
) {

    override val primaryKey: String = "discountId"

    override val pagingValidation: PagingValidation get() = PagingValidation.NON_NEGATIVE

    override fun toDTO(entity: OrderItemToDiscountEntity): Discount = Discount(entity.key.discountId)

    @GraphQLDescription("The resulting entities.")
    override suspend fun nodes(): List<Discount> = super.nodes()

    @GraphQLDescription("Whether this connection has a next page.")
    override suspend fun hasNextPage(): Boolean = super.hasNextPage()

    @GraphQLDescription("The total amount of items in this connection.")
    override suspend fun totalCount(): Int = super.totalCount()

}
