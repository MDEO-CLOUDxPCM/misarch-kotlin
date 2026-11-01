package org.misarch.user.graphql.model.connection

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.ShareableDirective
import org.misarch.common.graphql.AuthorizedUser
import org.misarch.common.graphql.connection.BaseConnection
import org.misarch.common.graphql.connection.BaseOrderField
import org.misarch.common.graphql.connection.Ordering
import org.misarch.common.persistence.Condition
import org.misarch.user.graphql.model.User
import org.misarch.user.persistence.model.UserEntity
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate

/**
 * A GraphQL connection for [User]s.
 *
 * @param first The maximum number of items to return
 * @param skip The number of items to skip
 * @param predicate The predicate to filter the items by
 * @param order The order to sort the items by
 * @param entityTemplate executes the queries
 * @param authorizedUser The authorized user
 */
@GraphQLDescription("A connection to a list of `User` values.")
@ShareableDirective
class UserConnection(
    first: Int?,
    skip: Int?,
    predicate: Condition?,
    order: UserOrder?,
    entityTemplate: R2dbcEntityTemplate,
    authorizedUser: AuthorizedUser?,
) : BaseConnection<User, UserEntity>(
    first,
    skip,
    null,
    predicate,
    (order ?: UserOrder.DEFAULT).toOrderBy(UserOrderField.ID),
    entityTemplate,
    UserEntity::class,
    authorizedUser,
) {

}

@GraphQLDescription("User order fields")
enum class UserOrderField(override vararg val columns: String) : BaseOrderField {
    @GraphQLDescription("Order users by their id")
    ID("id"),

    @GraphQLDescription("Order users by their username")
    USERNAME("username", "id"),
}

@GraphQLDescription("User order")
class UserOrder(
    @property:GraphQLDescription("The direction to order by")
    override val direction: OrderDirection?,
    @property:GraphQLDescription("The field to order by")
    override val field: UserOrderField?
) : Ordering<UserOrderField> {

    companion object {
        val DEFAULT = UserOrder(OrderDirection.ASC, UserOrderField.ID)
    }
}
