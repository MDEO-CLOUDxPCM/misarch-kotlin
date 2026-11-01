package org.misarch.wishlist.graphql.model.connection

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.ShareableDirective
import org.misarch.common.graphql.connection.BaseConnection
import org.misarch.common.graphql.connection.BaseOrderField
import org.misarch.common.graphql.connection.PagingValidation
import org.misarch.common.persistence.Condition
import org.misarch.wishlist.graphql.model.Wishlist
import org.misarch.wishlist.persistence.model.WishlistEntity
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate

/**
 * A GraphQL connection for [Wishlist]s.
 *
 * @param first The maximum number of items to return (default [DEFAULT_PAGE_SIZE])
 * @param skip The number of items to skip
 * @param predicate The predicate to filter the items by
 * @param order The order to sort the items by
 * @param entityTemplate executes the queries
 */
@GraphQLDescription("A connection of wishlists.")
@ShareableDirective
class WishlistConnection(
    first: Int?,
    skip: Int?,
    predicate: Condition?,
    order: WishlistOrderInput?,
    entityTemplate: R2dbcEntityTemplate,
) : BaseConnection<Wishlist, WishlistEntity>(
    first ?: DEFAULT_PAGE_SIZE,
    skip,
    null,
    predicate,
    (order ?: WishlistOrderInput.DEFAULT).toOrderBy(),
    entityTemplate,
    WishlistEntity::class,
    null,
) {

    /** `first: 0` is allowed (an empty page), negative arguments are errors */
    override val pagingValidation: PagingValidation get() = PagingValidation.NON_NEGATIVE

    @GraphQLDescription("The resulting entities.")
    override suspend fun nodes(): List<Wishlist> = super.nodes()

    @GraphQLDescription("Whether this connection has a next page.")
    override suspend fun hasNextPage(): Boolean = super.hasNextPage()

    @GraphQLDescription("The total amount of items in this connection.")
    override suspend fun totalCount(): Int = super.totalCount()

    companion object {
        /** Page size if `first` is not given */
        const val DEFAULT_PAGE_SIZE = 25
    }
}

@GraphQLDescription("Describes the fields that a wishlist can be ordered by.")
enum class WishlistOrderField(override vararg val columns: String) : BaseOrderField {
    @GraphQLDescription("Orders by \"id\".")
    ID("id"),

    @GraphQLDescription("Orders by \"user_id\".")
    USER_ID("userId"),

    @GraphQLDescription("Orders by \"name\".")
    NAME("name"),

    @GraphQLDescription("Orders by \"created_at\".")
    CREATED_AT("createdAt"),

    @GraphQLDescription("Orders by \"last_updated_at\".")
    LAST_UPDATED_AT("lastUpdatedAt")
}

@GraphQLDescription("Specifies the order of wishlists.")
class WishlistOrderInput(
    @property:GraphQLDescription("Order direction of wishlists.")
    val direction: OrderDirection? = null,
    @property:GraphQLDescription("Field that wishlists should be ordered by.")
    val field: WishlistOrderField? = null
) {

    /** SQL order items; ties are ordered by id (descending) */
    fun toOrderBy(): List<String> {
        val direction = (direction ?: OrderDirection.ASC).sql
        val columns = (field ?: WishlistOrderField.ID).columns
        return columns.map { "$it $direction" } + if ("id" in columns) emptyList() else listOf("id DESC")
    }

    companion object {
        val DEFAULT = WishlistOrderInput(OrderDirection.ASC, WishlistOrderField.ID)
    }
}
