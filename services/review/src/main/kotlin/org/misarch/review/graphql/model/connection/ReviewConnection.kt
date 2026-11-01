package org.misarch.review.graphql.model.connection

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.ShareableDirective
import org.misarch.common.graphql.connection.BaseConnection
import org.misarch.common.graphql.connection.BaseOrderField
import org.misarch.common.graphql.connection.Ordering
import org.misarch.common.graphql.connection.PagingValidation
import org.misarch.common.persistence.Condition
import org.misarch.review.graphql.model.Review
import org.misarch.review.persistence.model.ReviewEntity
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate

/**
 * A GraphQL connection for [Review]s.
 *
 * @param first The maximum number of items to return (default [DEFAULT_PAGE_SIZE])
 * @param skip The number of items to skip
 * @param predicate The predicate to filter the items by
 * @param order The order to sort the items by
 * @param entityTemplate executes the queries
 */
@GraphQLDescription("A connection of reviews.")
@ShareableDirective
class ReviewConnection(
    first: Int?,
    skip: Int?,
    predicate: Condition?,
    order: ReviewOrder?,
    entityTemplate: R2dbcEntityTemplate,
) : BaseConnection<Review, ReviewEntity>(
    first ?: DEFAULT_PAGE_SIZE,
    skip,
    null,
    predicate,
    (order ?: ReviewOrder.DEFAULT).toOrderBy(ReviewOrderField.ID, TIE_BREAK),
    entityTemplate,
    ReviewEntity::class,
    null,
) {

    /** `first: 0` is allowed (an empty page), negative arguments are errors */
    override val pagingValidation: PagingValidation get() = PagingValidation.NON_NEGATIVE

    @GraphQLDescription("The resulting entities.")
    override suspend fun nodes(): List<Review> = super.nodes()

    @GraphQLDescription("Whether this connection has a next page.")
    override suspend fun hasNextPage(): Boolean = super.hasNextPage()

    @GraphQLDescription("The total amount of items in this connection.")
    override suspend fun totalCount(): Int = super.totalCount()

    companion object {
        /** Page size if `first` is not given */
        const val DEFAULT_PAGE_SIZE = 25

        /** Reviews with the same value of the order field are ordered by descending id. */
        val TIE_BREAK = listOf("id DESC")
    }
}


@GraphQLDescription("Describes the fields that a review can be ordered by.")
enum class ReviewOrderField(override vararg val columns: String) : BaseOrderField {
    @GraphQLDescription("Orders by \"id\".")
    ID("id"),

    @GraphQLDescription("Orders by \"user_id\".")
    USER_ID("userId"),

    @GraphQLDescription("Orders by \"product_variant\".")
    PRODUCT_VARIANT("productVariantId", "productId"),

    @GraphQLDescription("Orders by \"rating\".")
    RATING("stars"),

    @GraphQLDescription("Orders by \"created_at\".")
    CREATED_AT("createdAt")
}

@GraphQLDescription("Specifies the order of reviews.")
class ReviewOrder(
    @property:GraphQLDescription("Order direction of reviews.")
    override val direction: OrderDirection? = null,
    @property:GraphQLDescription("Field that reviews should be ordered by.")
    override val field: ReviewOrderField? = null
) : Ordering<ReviewOrderField> {

    companion object {
        val DEFAULT = ReviewOrder(OrderDirection.ASC, ReviewOrderField.ID)
    }
}
