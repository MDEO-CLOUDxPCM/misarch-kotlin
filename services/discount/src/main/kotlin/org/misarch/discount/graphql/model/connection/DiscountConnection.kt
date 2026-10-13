package org.misarch.discount.graphql.model.connection

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.ShareableDirective
import org.misarch.common.graphql.AuthorizedUser
import org.misarch.common.graphql.connection.BaseConnection
import org.misarch.common.graphql.connection.PagingValidation
import org.misarch.common.graphql.connection.BaseOrder
import org.misarch.common.graphql.connection.BaseOrderField
import org.misarch.common.graphql.connection.OrderDirection
import org.misarch.common.persistence.Condition
import org.misarch.discount.graphql.model.Discount
import org.misarch.discount.persistence.model.DiscountEntity
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate

/**
 * A GraphQL connection for [Discount]s.
 *
 * @param first The maximum number of items to return
 * @param skip The number of items to skip
 * @param predicate The predicate to filter the items by
 * @param order The order to sort the items by
 * @param entityTemplate executes the queries
 * @param authorizedUser The authorized user
 * @param join An optional join clause (alias `j`, main table alias `t`)
 */
@GraphQLDescription("A connection to a list of `Discount` values.")
@ShareableDirective
class DiscountConnection(
    first: Int?,
    skip: Int?,
    predicate: Condition?,
    order: DiscountOrder?,
    entityTemplate: R2dbcEntityTemplate,
    authorizedUser: AuthorizedUser?,
    join: String? = null
) : BaseConnection<Discount, DiscountEntity>(
    first,
    skip,
    null,
    predicate,
    (order ?: DiscountOrder.DEFAULT).toOrderBy(DiscountOrderField.ID),
    entityTemplate,
    DiscountEntity::class,
    authorizedUser,
    join
) {

    override val pagingValidation: PagingValidation get() = PagingValidation.QUERYDSL
}

@GraphQLDescription("Discount order fields")
enum class DiscountOrderField(override vararg val columns: String) : BaseOrderField {
    @GraphQLDescription("Order discounts by their id")
    ID("id"),

    @GraphQLDescription("Order discounts by the valid from date")
    VALID_FROM("validFrom", "id"),

    @GraphQLDescription("Order discounts by the valid until date")
    VALID_UNTIL("validUntil", "id"),
}

@GraphQLDescription("Discount order")
class DiscountOrder(
    direction: OrderDirection?, field: DiscountOrderField?
) : BaseOrder<DiscountOrderField>(direction, field) {

    companion object {
        val DEFAULT = DiscountOrder(OrderDirection.ASC, DiscountOrderField.ID)
    }
}
