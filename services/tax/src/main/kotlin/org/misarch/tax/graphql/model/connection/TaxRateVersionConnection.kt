package org.misarch.tax.graphql.model.connection

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.ShareableDirective
import org.misarch.common.graphql.AuthorizedUser
import org.misarch.common.graphql.connection.BaseConnection
import org.misarch.common.graphql.connection.BaseOrder
import org.misarch.common.graphql.connection.BaseOrderField
import org.misarch.common.graphql.connection.OrderDirection
import org.misarch.common.graphql.connection.PagingValidation
import org.misarch.common.persistence.Condition
import org.misarch.tax.graphql.model.TaxRateVersion
import org.misarch.tax.persistence.model.TaxRateVersionEntity
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate

/**
 * A GraphQL connection for [TaxRateVersion]s.
 *
 * @param first The maximum number of items to return
 * @param skip The number of items to skip
 * @param predicate The predicate to filter the items by
 * @param order The order to sort the items by
 * @param entityTemplate executes the queries
 * @param authorizedUser The authorized user
 */
@GraphQLDescription("A connection to a list of `TaxRateVersion` values.")
@ShareableDirective
class TaxRateVersionConnection(
    first: Int?,
    skip: Int?,
    predicate: Condition?,
    order: TaxRateVersionOrder?,
    entityTemplate: R2dbcEntityTemplate,
    authorizedUser: AuthorizedUser?,
) : BaseConnection<TaxRateVersion, TaxRateVersionEntity>(
    first,
    skip,
    null,
    predicate,
    (order ?: TaxRateVersionOrder.DEFAULT).toOrderBy(TaxRateVersionOrderField.ID),
    entityTemplate,
    TaxRateVersionEntity::class,
    authorizedUser,
) {

    override val pagingValidation: PagingValidation get() = PagingValidation.QUERYDSL
}

@GraphQLDescription("TaxRateVersion order fields")
enum class TaxRateVersionOrderField(override vararg val columns: String) : BaseOrderField {
    @GraphQLDescription("Order TaxRateVersions by their id")
    ID("id"),

    @GraphQLDescription("Order TaxRateVersions by their version")
    VERSION("version", "id"),

    @GraphQLDescription("Order TaxRateVersions by their creation date")
    CREATED_AT("createdAt", "id")
}

@GraphQLDescription("TaxRateVersion order")
class TaxRateVersionOrder(
    direction: OrderDirection?, field: TaxRateVersionOrderField?
) : BaseOrder<TaxRateVersionOrderField>(direction, field) {

    companion object {
        val DEFAULT = TaxRateVersionOrder(OrderDirection.ASC, TaxRateVersionOrderField.ID)
    }
}
