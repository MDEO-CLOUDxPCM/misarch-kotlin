package org.misarch.catalog.graphql.model.connection

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.ShareableDirective
import org.misarch.catalog.graphql.model.ProductVariantVersion
import org.misarch.catalog.persistence.model.ProductVariantVersionEntity
import org.misarch.common.graphql.AuthorizedUser
import org.misarch.common.graphql.connection.BaseConnection
import org.misarch.common.graphql.connection.BaseOrder
import org.misarch.common.graphql.connection.BaseOrderField
import org.misarch.common.graphql.connection.OrderDirection
import org.misarch.common.persistence.Condition
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate

/**
 * A GraphQL connection for [ProductVariantVersion]s.
 *
 * @param first The maximum number of items to return
 * @param skip The number of items to skip
 * @param predicate The predicate to filter the items by
 * @param order The order to sort the items by
 * @param entityTemplate executes the queries
 * @param authorizedUser The authorized user
 * @param join An optional join clause (main table alias `t`)
 */
@GraphQLDescription("A connection to a list of `ProductVariantVersion` values.")
@ShareableDirective
class ProductVariantVersionConnection(
    first: Int?,
    skip: Int?,
    predicate: Condition?,
    order: ProductVariantVersionOrder?,
    entityTemplate: R2dbcEntityTemplate,
    authorizedUser: AuthorizedUser?,
    join: String? = null
) : BaseConnection<ProductVariantVersion, ProductVariantVersionEntity>(
    first,
    skip,
    null,
    predicate,
    (order ?: ProductVariantVersionOrder.DEFAULT).toOrderBy(ProductVariantVersionOrderField.ID),
    entityTemplate,
    ProductVariantVersionEntity::class,
    authorizedUser,
    join
) {

}

@GraphQLDescription("ProductVariantVersion order fields")
enum class ProductVariantVersionOrderField(override vararg val columns: String) :
    BaseOrderField {
    @GraphQLDescription("Order productVariantVersions by their id")
    ID("id"),

    @GraphQLDescription("Order productVariantVersions by their version")
    VERSION("version", "id"),

    @GraphQLDescription("Order productVariantVersions by their creation date")
    CREATED_AT("createdAt", "id")
}

@GraphQLDescription("ProductVariantVersion order")
class ProductVariantVersionOrder(
    direction: OrderDirection?, field: ProductVariantVersionOrderField?
) : BaseOrder<ProductVariantVersionOrderField>(direction, field) {

    companion object {
        val DEFAULT = ProductVariantVersionOrder(OrderDirection.ASC, ProductVariantVersionOrderField.ID)
    }
}
