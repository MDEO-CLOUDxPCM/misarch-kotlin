package org.misarch.address.graphql.model.connection

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.ShareableDirective
import org.misarch.address.graphql.model.Address
import org.misarch.address.graphql.model.UserAddress
import org.misarch.address.persistence.model.AddressEntity
import org.misarch.common.graphql.AuthorizedUser
import org.misarch.common.graphql.connection.BaseConnection
import org.misarch.common.graphql.connection.BaseFilter
import org.misarch.common.graphql.connection.BaseOrderField
import org.misarch.common.graphql.connection.Ordering
import org.misarch.common.persistence.Condition
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate

/**
 * A GraphQL connection for [UserAddress]es.
 *
 * @param first The maximum number of items to return
 * @param skip The number of items to skip
 * @param filter The filter to apply to the items
 * @param predicate The predicate to filter the items by
 * @param order The order to sort the items by
 * @param entityTemplate executes the queries
 * @param authorizedUser The authorized user
 */
@GraphQLDescription("A connection to a list of `Address` values.")
@ShareableDirective
class UserAddressConnection(
    first: Int?,
    skip: Int?,
    filter: UserAddressFilter?,
    predicate: Condition?,
    order: UserAddressOrder?,
    entityTemplate: R2dbcEntityTemplate,
    authorizedUser: AuthorizedUser?,
) : BaseConnection<Address, AddressEntity>(
    first,
    skip,
    filter,
    predicate,
    (order ?: UserAddressOrder.DEFAULT).toOrderBy(UserAddressOrderField.ID),
    entityTemplate,
    AddressEntity::class,
    authorizedUser,
) {

    @GraphQLDescription("The resulting items.")
    override suspend fun nodes(): List<UserAddress> {
        return super.nodes().map { it as UserAddress }
    }

}

@GraphQLDescription("User address order fields")
enum class UserAddressOrderField(override vararg val columns: String) : BaseOrderField {
    @GraphQLDescription("Order addresss by their id")
    ID("id"),
}

@GraphQLDescription("User address order")
class UserAddressOrder(
    @property:GraphQLDescription("The direction to order by")
    override val direction: OrderDirection?,
    @property:GraphQLDescription("The field to order by")
    override val field: UserAddressOrderField?
) : Ordering<UserAddressOrderField> {

    companion object {
        val DEFAULT = UserAddressOrder(OrderDirection.ASC, UserAddressOrderField.ID)
    }
}

@GraphQLDescription("User address filter")
class UserAddressFilter(
    val isArchived: Boolean?
) : BaseFilter {

    override fun toCondition(): Condition? {
        return if (isArchived != null) {
            if (isArchived) Condition.isNotNull("archivedAt") else Condition.isNull("archivedAt")
        } else {
            null
        }
    }

}
