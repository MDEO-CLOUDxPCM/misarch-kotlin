package org.misarch.inventory.graphql.model.connection

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import org.misarch.common.graphql.connection.BaseFilter
import org.misarch.common.graphql.connection.NullableNodesConnection
import org.misarch.common.graphql.connection.Ordering
import org.misarch.common.persistence.Condition
import org.misarch.inventory.graphql.model.ProductItem
import org.misarch.inventory.persistence.model.ProductItemEntity
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate

/**
 * A GraphQL connection for [ProductItem]s.
 *
 * @param first The maximum number of items to return
 * @param skip The number of items to skip
 * @param filter The filter to apply to the items
 * @param predicate The predicate to filter the items by
 * @param order The order to sort the items by
 * @param entityTemplate executes the queries
 */
@GraphQLDescription("A connection of product items")
class ProductItemConnection(
    first: Int?,
    skip: Int?,
    filter: BaseFilter?,
    predicate: Condition?,
    order: ProductItemOrder?,
    entityTemplate: R2dbcEntityTemplate,
) : NullableNodesConnection<ProductItem, ProductItemEntity>(
    first,
    skip,
    filter,
    predicate,
    (order ?: ProductItemOrder.DEFAULT).toOrderBy(ProductItemOrderField.ID),
    entityTemplate,
    ProductItemEntity::class,
    null,
) {

    @GraphQLDescription("The resulting items")
    override suspend fun nodes(): List<ProductItem>? = super.nodes()
}
