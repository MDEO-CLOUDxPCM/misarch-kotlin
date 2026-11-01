package org.misarch.catalog.graphql.model.connection

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.ShareableDirective
import org.misarch.catalog.graphql.model.Product
import org.misarch.catalog.persistence.model.ProductEntity
import org.misarch.common.graphql.AuthorizedUser
import org.misarch.common.graphql.connection.BaseConnection
import org.misarch.common.graphql.connection.BaseFilter
import org.misarch.common.graphql.connection.BaseOrder
import org.misarch.common.graphql.connection.BaseOrderField
import org.misarch.common.graphql.connection.OrderDirection
import org.misarch.common.persistence.Condition
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate

/**
 * A GraphQL connection for [Product]s.
 *
 * @param first The maximum number of items to return
 * @param skip The number of items to skip
 * @param filter The filter to apply to the items
 * @param predicate The predicate to filter the items by
 * @param order The order to sort the items by
 * @param entityTemplate executes the queries
 * @param join An optional join clause (main table alias `t`)
 */
@GraphQLDescription("A connection to a list of `Product` values.")
@ShareableDirective
class ProductConnection(
    first: Int?,
    skip: Int?,
    filter: ProductFilter?,
    predicate: Condition?,
    order: ProductOrder?,
    entityTemplate: R2dbcEntityTemplate,
    authorizedUser: AuthorizedUser?,
    join: String? = null
) : BaseConnection<Product, ProductEntity>(
    first,
    skip,
    filter,
    predicate,
    (order ?: ProductOrder.DEFAULT).toOrderBy(ProductOrderField.ID),
    entityTemplate,
    ProductEntity::class,
    authorizedUser,
    join
) {


    override fun authorizedUserFilter(): Condition? {
        return if (authorizedUser?.isEmployee == true) {
            null
        } else {
            Condition.raw("t.isPubliclyVisible = TRUE")
        }
    }
}

@GraphQLDescription("Product order fields")
enum class ProductOrderField(override vararg val columns: String) : BaseOrderField {
    @GraphQLDescription("Order products by their id")
    ID("id"),

    @GraphQLDescription("Order products by their internal name")
    INTERNAL_NAME("internalName", "id")
}

@GraphQLDescription("Product order")
class ProductOrder(
    direction: OrderDirection?, field: ProductOrderField?
) : BaseOrder<ProductOrderField>(direction, field) {

    companion object {
        val DEFAULT = ProductOrder(OrderDirection.ASC, ProductOrderField.ID)
    }
}

@GraphQLDescription("Product filter")
class ProductFilter(
    val isPubliclyVisible: Boolean?
) : BaseFilter {

    override fun toCondition(): Condition? {
        return if (isPubliclyVisible != null) {
            Condition.eq("t.isPubliclyVisible", isPubliclyVisible)
        } else {
            null
        }
    }
}
