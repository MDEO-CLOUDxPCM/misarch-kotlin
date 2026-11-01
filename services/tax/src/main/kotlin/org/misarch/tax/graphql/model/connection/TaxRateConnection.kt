package org.misarch.tax.graphql.model.connection

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.ShareableDirective
import org.misarch.common.graphql.AuthorizedUser
import org.misarch.common.graphql.connection.BaseConnection
import org.misarch.common.graphql.connection.BaseOrderField
import org.misarch.common.graphql.connection.Ordering
import org.misarch.common.persistence.Condition
import org.misarch.tax.graphql.model.TaxRate
import org.misarch.tax.persistence.model.TaxRateEntity
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate

/**
 * A GraphQL connection for [TaxRate]s.
 *
 * @param first The maximum number of items to return
 * @param skip The number of items to skip
 * @param predicate The predicate to filter the items by
 * @param order The order to sort the items by
 * @param entityTemplate executes the queries
 * @param authorizedUser The authorized user
 */
@GraphQLDescription("A connection to a list of `TaxRate` values.")
@ShareableDirective
class TaxRateConnection(
    first: Int?,
    skip: Int?,
    predicate: Condition?,
    order: TaxRateOrder?,
    entityTemplate: R2dbcEntityTemplate,
    authorizedUser: AuthorizedUser?,
) : BaseConnection<TaxRate, TaxRateEntity>(
    first,
    skip,
    null,
    predicate,
    (order ?: TaxRateOrder.DEFAULT).toOrderBy(TaxRateOrderField.ID),
    entityTemplate,
    TaxRateEntity::class,
    authorizedUser,
) {

}

@GraphQLDescription("TaxRate order fields")
enum class TaxRateOrderField(override vararg val columns: String) : BaseOrderField {
    @GraphQLDescription("Order TaxRates by their id")
    ID("id"),

    @GraphQLDescription("Order TaxRates by their name")
    NAME("name", "id"),
}

@GraphQLDescription("TaxRate order")
class TaxRateOrder(
    @property:GraphQLDescription("The direction to order by")
    override val direction: OrderDirection?,
    @property:GraphQLDescription("The field to order by")
    override val field: TaxRateOrderField?
) : Ordering<TaxRateOrderField> {

    companion object {
        val DEFAULT = TaxRateOrder(OrderDirection.ASC, TaxRateOrderField.ID)
    }
}
