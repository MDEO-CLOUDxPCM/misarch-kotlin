package org.misarch.inventory.graphql.model.connection

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import org.misarch.common.graphql.connection.BaseFilter
import org.misarch.common.graphql.connection.connectionQuery
import org.misarch.common.persistence.Condition
import org.misarch.inventory.graphql.model.ProductItem
import org.misarch.inventory.persistence.model.ProductItemEntity
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate

/**
 * A GraphQL connection for [ProductItem]s (each field runs its own statement, only when selected).
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
) {

    private val query = entityTemplate.connectionQuery(
        ProductItemEntity::class,
        Condition.allOf(predicate, filter?.toCondition()),
        (order ?: ProductItemOrder.DEFAULT).toOrderBy(),
        first,
        skip
    )

    @GraphQLDescription("The resulting items")
    suspend fun nodes(): List<ProductItem>? {
        return query.nodes().map { it.toDTO() }
    }

    @GraphQLDescription("The total amount of items in this connection")
    suspend fun totalCount(): Int {
        return query.totalCount()
    }

    @GraphQLDescription("Whether this connection has a next page")
    suspend fun hasNextPage(): Boolean {
        return query.hasNextPage()
    }

}
