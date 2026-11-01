package org.misarch.shipment.graphql.model.connection

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.ShareableDirective
import org.misarch.common.graphql.AuthorizedUser
import org.misarch.common.graphql.connection.BaseConnection
import org.misarch.common.graphql.connection.BaseFilter
import org.misarch.common.graphql.connection.BaseOrderField
import org.misarch.common.graphql.connection.Ordering
import org.misarch.common.persistence.Condition
import org.misarch.shipment.graphql.model.ShipmentMethod
import org.misarch.shipment.persistence.model.ShipmentMethodEntity
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate

/**
 * A GraphQL connection for [ShipmentMethod]s.
 *
 * @param first The maximum number of items to return
 * @param skip The number of items to skip
 * @param filter The filter to apply to the items
 * @param predicate The predicate to filter the items by
 * @param order The order to sort the items by
 * @param entityTemplate executes the queries
 * @param authorizedUser The authorized user
 */
@GraphQLDescription("A connection to a list of `ShipmentMethod` values.")
@ShareableDirective
class ShipmentMethodConnection(
    first: Int?,
    skip: Int?,
    filter: ShipmentMethodFilter?,
    predicate: Condition?,
    order: ShipmentMethodOrder?,
    entityTemplate: R2dbcEntityTemplate,
    authorizedUser: AuthorizedUser?,
) : BaseConnection<ShipmentMethod, ShipmentMethodEntity>(
    first,
    skip,
    filter,
    predicate,
    (order ?: ShipmentMethodOrder.DEFAULT).toOrderBy(ShipmentMethodOrderField.ID),
    entityTemplate,
    ShipmentMethodEntity::class,
    authorizedUser,
) {


}

@GraphQLDescription("Shipment method order fields")
enum class ShipmentMethodOrderField(override vararg val columns: String) : BaseOrderField {
    @GraphQLDescription("Order shipment methods by their id")
    ID("id"),


}

@GraphQLDescription("Shipment method order")
class ShipmentMethodOrder(
    @property:GraphQLDescription("The direction to order by")
    override val direction: OrderDirection?,
    @property:GraphQLDescription("The field to order by")
    override val field: ShipmentMethodOrderField?
) : Ordering<ShipmentMethodOrderField> {

    companion object {
        val DEFAULT = ShipmentMethodOrder(OrderDirection.ASC, ShipmentMethodOrderField.ID)
    }
}

@GraphQLDescription("Shipment method filter")
class ShipmentMethodFilter(
    @GraphQLDescription("Filter shipment methods by their archived status")
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
