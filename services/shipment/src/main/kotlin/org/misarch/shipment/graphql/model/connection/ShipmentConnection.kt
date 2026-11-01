package org.misarch.shipment.graphql.model.connection

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.ShareableDirective
import org.misarch.common.graphql.AuthorizedUser
import org.misarch.common.graphql.connection.BaseConnection
import org.misarch.common.graphql.connection.BaseFilter
import org.misarch.common.graphql.connection.BaseOrder
import org.misarch.common.graphql.connection.BaseOrderField
import org.misarch.common.graphql.connection.OrderDirection
import org.misarch.common.persistence.Condition
import org.misarch.shipment.graphql.model.Shipment
import org.misarch.shipment.graphql.model.ShipmentStatus
import org.misarch.shipment.persistence.model.ShipmentEntity
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate

/**
 * A GraphQL connection for [Shipment]s.
 *
 * @param first The maximum number of items to return
 * @param skip The number of items to skip
 * @param filter The filter to apply to the items
 * @param predicate The predicate to filter the items by
 * @param order The order to sort the items by
 * @param entityTemplate executes the queries
 * @param authorizedUser The authorized user
 */
@GraphQLDescription("A connection to a list of `Shipment` values.")
@ShareableDirective
class ShipmentConnection(
    first: Int?,
    skip: Int?,
    filter: ShipmentFilter?,
    predicate: Condition?,
    order: ShipmentOrder?,
    entityTemplate: R2dbcEntityTemplate,
    authorizedUser: AuthorizedUser?,
) : BaseConnection<Shipment, ShipmentEntity>(
    first,
    skip,
    filter,
    predicate,
    (order ?: ShipmentOrder.DEFAULT).toOrderBy(ShipmentOrderField.ID),
    entityTemplate,
    ShipmentEntity::class,
    authorizedUser,
) {


}

@GraphQLDescription("Shipment  order fields")
enum class ShipmentOrderField(override vararg val columns: String) : BaseOrderField {
    @GraphQLDescription("Order shipment s by their id")
    ID("id"),

}

@GraphQLDescription("Shipment  order")
class ShipmentOrder(
    direction: OrderDirection?, field: ShipmentOrderField?
) : BaseOrder<ShipmentOrderField>(direction, field) {

    companion object {
        val DEFAULT = ShipmentOrder(OrderDirection.ASC, ShipmentOrderField.ID)
    }
}

@GraphQLDescription("Shipment filter")
class ShipmentFilter(
    @GraphQLDescription("Filter shipments by their status")
    val status: ShipmentStatus?
) : BaseFilter {

    /**
     * Binds the enum name (the column stores it). Fix (docs/fixes/shipment.md): the original bound the enum constant
     * itself, which the R2DBC Postgres driver cannot encode, so every query with a status filter failed
     * (`Cannot encode parameter of type io.r2dbc.spi.Parameters$InParameter (PENDING)`).
     */
    override fun toCondition(): Condition? {
        return if (status != null) {
            Condition.eq("status", status.name)
        } else {
            null
        }
    }

}
