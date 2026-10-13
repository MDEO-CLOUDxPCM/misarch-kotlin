package org.misarch.catalog.graphql.model.connection

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.ShareableDirective
import org.misarch.catalog.graphql.model.ProductVariant
import org.misarch.catalog.persistence.model.ProductVariantEntity
import org.misarch.common.graphql.AuthorizedUser
import org.misarch.common.graphql.connection.BaseConnection
import org.misarch.common.graphql.connection.PagingValidation
import org.misarch.common.graphql.connection.BaseFilter
import org.misarch.common.graphql.connection.BaseOrder
import org.misarch.common.graphql.connection.BaseOrderField
import org.misarch.common.graphql.connection.OrderDirection
import org.misarch.common.persistence.Condition
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate

/**
 * A GraphQL connection for [ProductVariant]s.
 *
 * @param first The maximum number of items to return
 * @param skip The number of items to skip
 * @param filter The filter to apply to the items
 * @param predicate The predicate to filter the items by
 * @param order The order to sort the items by
 * @param entityTemplate executes the queries
 * @param authorizedUser The authorized user
 * @param join An optional join clause (main table alias `t`)
 */
@GraphQLDescription("A connection to a list of `ProductVariant` values.")
@ShareableDirective
class ProductVariantConnection(
    first: Int?,
    skip: Int?,
    filter: ProductVariantFilter?,
    predicate: Condition?,
    order: ProductVariantOrder?,
    entityTemplate: R2dbcEntityTemplate,
    authorizedUser: AuthorizedUser?,
    join: String? = null
) : BaseConnection<ProductVariant, ProductVariantEntity>(
    first,
    skip,
    filter,
    predicate,
    (order ?: ProductVariantOrder.DEFAULT).toOrderBy(ProductVariantOrderField.ID),
    entityTemplate,
    ProductVariantEntity::class,
    authorizedUser,
    join
) {

    override val pagingValidation: PagingValidation get() = PagingValidation.QUERYDSL

    override fun authorizedUserFilter(): Condition? {
        return if (authorizedUser?.isEmployee == true) {
            null
        } else {
            Condition.raw("t.isPubliclyVisible = TRUE")
        }
    }
}

@GraphQLDescription("ProductVariant order fields")
enum class ProductVariantOrderField(override vararg val columns: String) : BaseOrderField {
    @GraphQLDescription("Order productVariants by their id")
    ID("id")
}

@GraphQLDescription("ProductVariant order")
class ProductVariantOrder(
    direction: OrderDirection?, field: ProductVariantOrderField?
) : BaseOrder<ProductVariantOrderField>(direction, field) {

    companion object {
        val DEFAULT = ProductVariantOrder(OrderDirection.ASC, ProductVariantOrderField.ID)
    }
}

@GraphQLDescription("Product variant filter")
class ProductVariantFilter(
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
