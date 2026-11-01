package org.misarch.wishlist.graphql.model.connection

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.ShareableDirective
import org.misarch.common.graphql.connection.BaseConnection
import org.misarch.common.graphql.connection.PagingValidation
import org.misarch.common.persistence.Condition
import org.misarch.wishlist.graphql.model.ProductVariant
import org.misarch.wishlist.persistence.model.ProductVariantEntity
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate

/**
 * A GraphQL connection for [ProductVariant]s.
 *
 * @param first The maximum number of items to return
 * @param skip The number of items to skip
 * @param predicate The predicate to filter the items by
 * @param order The order to sort the items by
 * @param entityTemplate executes the queries
 * @param join optional join clause (e.g. the link table of a wishlist)
 */
@GraphQLDescription("A connection of product variants.")
@ShareableDirective
class ProductVariantConnection(
    first: Int?,
    skip: Int?,
    predicate: Condition?,
    order: CommonOrder?,
    entityTemplate: R2dbcEntityTemplate,
    join: String?,
) : BaseConnection<ProductVariant, ProductVariantEntity>(
    first,
    skip,
    null,
    predicate,
    (order ?: CommonOrder.DEFAULT).toOrderBy(CommonOrderField.ID),
    entityTemplate,
    ProductVariantEntity::class,
    null,
    join,
) {

    /** `first: 0` is allowed (an empty page), negative arguments are errors */
    override val pagingValidation: PagingValidation get() = PagingValidation.NON_NEGATIVE

    @GraphQLDescription("The resulting entities.")
    override suspend fun nodes(): List<ProductVariant> = super.nodes()

    @GraphQLDescription("Whether this connection has a next page.")
    override suspend fun hasNextPage(): Boolean = super.hasNextPage()

    @GraphQLDescription("The total amount of items in this connection.")
    override suspend fun totalCount(): Int = super.totalCount()

}
