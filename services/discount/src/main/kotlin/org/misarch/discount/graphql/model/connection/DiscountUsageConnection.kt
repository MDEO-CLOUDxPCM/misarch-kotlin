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
import org.misarch.discount.graphql.model.DiscountUsage
import org.misarch.discount.persistence.model.DiscountUsageEntity
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate

/**
 * A GraphQL connection for [DiscountUsage]s.
 *
 * @param first The maximum number of items to return
 * @param skip The number of items to skip
 * @param predicate The predicate to filter the items by
 * @param order The order to sort the items by
 * @param entityTemplate executes the queries
 * @param authorizedUser The authorized user
 */
@GraphQLDescription("A connection to a list of `DiscountUsage` values.")
@ShareableDirective
class DiscountUsageConnection(
    first: Int?,
    skip: Int?,
    predicate: Condition?,
    order: DiscountUsageOrder?,
    entityTemplate: R2dbcEntityTemplate,
    authorizedUser: AuthorizedUser?
) : BaseConnection<DiscountUsage, DiscountUsageEntity>(
    first,
    skip,
    null,
    predicate,
    (order ?: DiscountUsageOrder.DEFAULT).toOrderBy(DiscountUsageOrderField.ID),
    entityTemplate,
    DiscountUsageEntity::class,
    authorizedUser
) {

    override val pagingValidation: PagingValidation get() = PagingValidation.QUERYDSL
}

@GraphQLDescription("DiscountUsage order fields")
enum class DiscountUsageOrderField(override vararg val columns: String) : BaseOrderField {
    @GraphQLDescription("Order discount usages by their id")
    ID("id"),

    @GraphQLDescription("Order discount usages by their usages")
    USAGES("usages", "id"),
}

@GraphQLDescription("DiscountUsage order")
class DiscountUsageOrder(
    direction: OrderDirection?, field: DiscountUsageOrderField?
) : BaseOrder<DiscountUsageOrderField>(direction, field) {

    companion object {
        val DEFAULT = DiscountUsageOrder(OrderDirection.ASC, DiscountUsageOrderField.ID)
    }
}
