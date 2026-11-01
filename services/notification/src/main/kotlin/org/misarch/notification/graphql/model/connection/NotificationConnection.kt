package org.misarch.notification.graphql.model.connection

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.ShareableDirective
import org.misarch.common.graphql.AuthorizedUser
import org.misarch.common.graphql.connection.BaseConnection
import org.misarch.common.graphql.connection.BaseOrder
import org.misarch.common.graphql.connection.BaseOrderField
import org.misarch.common.graphql.connection.OrderDirection
import org.misarch.common.persistence.Condition
import org.misarch.notification.graphql.model.Notification
import org.misarch.notification.persistence.model.NotificationEntity
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate

/**
 * A GraphQL connection for [Notification]s.
 *
 * @param first The maximum number of items to return
 * @param skip The number of items to skip
 * @param predicate The predicate to filter the items by
 * @param order The order to sort the items by
 * @param entityTemplate executes the queries
 * @param authorizedUser The authorized user
 */
@GraphQLDescription("A connection to a list of `Notification` values.")
@ShareableDirective
class NotificationConnection(
    first: Int?,
    skip: Int?,
    predicate: Condition?,
    order: NotificationOrder?,
    entityTemplate: R2dbcEntityTemplate,
    authorizedUser: AuthorizedUser?,
) : BaseConnection<Notification, NotificationEntity>(
    first,
    skip,
    null,
    predicate,
    (order ?: NotificationOrder.DEFAULT).toOrderBy(NotificationOrderField.ID),
    entityTemplate,
    NotificationEntity::class,
    authorizedUser,
) {

}

@GraphQLDescription("Notification order fields")
enum class NotificationOrderField(override vararg val columns: String) : BaseOrderField {
    @GraphQLDescription("Order notifications by their id")
    ID("id"),
}

@GraphQLDescription("Notification order")
class NotificationOrder(
    direction: OrderDirection?, field: NotificationOrderField?
) : BaseOrder<NotificationOrderField>(direction, field) {

    companion object {
        val DEFAULT = NotificationOrder(OrderDirection.ASC, NotificationOrderField.ID)
    }
}
