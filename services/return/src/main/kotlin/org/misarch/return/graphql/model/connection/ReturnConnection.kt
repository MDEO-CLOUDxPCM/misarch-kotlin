package org.misarch.`return`.graphql.model.connection

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.ShareableDirective
import org.misarch.common.graphql.AuthorizedUser
import org.misarch.common.graphql.connection.BaseConnection
import org.misarch.common.graphql.connection.BaseOrder
import org.misarch.common.graphql.connection.BaseOrderField
import org.misarch.common.graphql.connection.OrderDirection
import org.misarch.common.persistence.Condition
import org.misarch.`return`.graphql.model.Return
import org.misarch.`return`.persistence.model.ReturnEntity
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate

/**
 * A GraphQL connection for [Return]s, always joined with `OrderEntity` (alias `j`).
 *
 * @param first The maximum number of items to return
 * @param skip The number of items to skip
 * @param predicate The predicate to filter the items by (may use `j.` columns of OrderEntity)
 * @param order The order to sort the items by
 * @param entityTemplate executes the queries
 * @param authorizedUser The authorized user
 */
@GraphQLDescription("A connection to a list of `Return` values.")
@ShareableDirective
class ReturnConnection(
    first: Int?,
    skip: Int?,
    predicate: Condition?,
    order: ReturnOrder?,
    entityTemplate: R2dbcEntityTemplate,
    authorizedUser: AuthorizedUser?,
) : BaseConnection<Return, ReturnEntity>(
    first,
    skip,
    null,
    predicate,
    (order ?: ReturnOrder.DEFAULT).toOrderBy(ReturnOrderField.ID),
    entityTemplate,
    ReturnEntity::class,
    authorizedUser,
    "INNER JOIN OrderEntity j ON j.id = t.orderId"
) {

    /**
     * Employees see all returns, other users only the returns of their own orders.
     */
    override fun authorizedUserFilter(): Condition? {
        val authorizedUser = authorizedUser
        return when {
            authorizedUser == null -> Condition.raw("FALSE")
            authorizedUser.isEmployee -> null
            else -> Condition.eq("j.userId", authorizedUser.id)
        }
    }

}

@GraphQLDescription("Return order fields")
enum class ReturnOrderField(override vararg val columns: String) : BaseOrderField {
    @GraphQLDescription("Order returns by their id")
    ID("id"),
}

@GraphQLDescription("Direction to sort returns in")
class ReturnOrder(
    direction: OrderDirection?, field: ReturnOrderField?
) : BaseOrder<ReturnOrderField>(direction, field) {

    companion object {
        val DEFAULT = ReturnOrder(OrderDirection.ASC, ReturnOrderField.ID)
    }
}
