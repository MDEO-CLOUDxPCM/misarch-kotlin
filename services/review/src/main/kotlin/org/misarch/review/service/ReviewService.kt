package org.misarch.review.service

import org.misarch.common.graphql.GraphQLErrorException
import org.misarch.common.rust.MongoCursorPagination
import org.misarch.common.rust.RustArgs
import org.misarch.common.rust.RustDateTime
import org.misarch.common.rust.RustPanic
import org.misarch.review.graphql.input.CreateReviewInput
import org.misarch.review.graphql.input.UpdateReviewInput
import org.misarch.review.graphql.model.OrderDirection
import org.misarch.review.graphql.model.ProductVariant
import org.misarch.review.graphql.model.Rating
import org.misarch.review.graphql.model.Review
import org.misarch.review.graphql.model.ReviewConnection
import org.misarch.review.graphql.model.ReviewOrderField
import org.misarch.review.graphql.model.ReviewOrderInput
import org.misarch.review.graphql.model.User
import org.misarch.review.persistence.ReviewRow
import org.misarch.review.persistence.ReviewStore
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Business logic of the review service (original: `graphql/mutation.rs`, `graphql/query.rs`, the `graphql/model` modules).
 * Exact semantics and messages: docs/specs/review.md §5.
 */
@Service
class ReviewService(private val store: ReviewStore) {

    /** Filter of a review connection (`doc! {...}` of the original). */
    sealed class Filter(val where: String?, val value: UUID?) {
        data object All : Filter(null, null)
        class User(id: UUID) : Filter("user_id = :filter", id)
        class Product(id: UUID) : Filter("product_variant_product_id = :filter", id)
        class ProductVariant(id: UUID) : Filter("product_variant_id = :filter", id)
    }

    // ---------------------------------------------------------------- reads

    /**
     * `query_object_optional` for reviews: null if missing; an undeserializable document (`FourStarst`) fails like
     * a Mongo deserialization error.
     */
    suspend fun findReview(id: UUID): Review? {
        val row = store.findReview(id) ?: return null
        return row.toReview() ?: throw notFound(id)
    }

    /** `query_object` for reviews: missing or undeserializable → not found. */
    private suspend fun loadReview(id: UUID): Review = findReview(id) ?: throw notFound(id)

    /**
     * Connection of §5.5 (mongodb-cursor-pagination semantics: default page size 25, `first + 1` rows fetched).
     * Arguments are coerced like async-graphql. FIXED (Q2, docs/fixes/review.md): with `skip > 0` the page is
     * `first` rows long and `hasNextPage` is "a further row exists" (the library returned `first + 1` rows and
     * `hasNextPage: false` when exactly `first + 1` rows remained).
     */
    suspend fun connection(filter: Filter, first: Int?, skip: Int?, orderBy: ReviewOrderInput?): ReviewConnection {
        val limit = RustArgs.unsigned(first) ?: MongoCursorPagination.DEFAULT_LIMIT
        val skipValue = RustArgs.unsigned(skip)?.takeIf { it > 0 }
        val order = (orderBy?.field ?: ReviewOrderField.ID).orderBy(orderBy?.direction ?: OrderDirection.ASC)
        val total = store.countReviews(filter.where, filter.value)
        if (total == 0L) return ReviewConnection(emptyList(), false, 0)
        val rows = store.findReviews(filter.where, filter.value, order, skipValue, limit + 1)
        val hasNextPage = rows.size.toLong() > limit
        val page = if (hasNextPage) rows.dropLast(1) else rows
        // rows written by older versions may be undeserializable (`FourStarst`, repaired by migration V2): the
        // original panicked here (pagination library `unwrap()`)
        val nodes = page.map { row ->
            row.toReview() ?: throw RustPanic(
                "called `Result::unwrap()` on an `Err` value: unknown variant `${row.rating}` (review ${row.id})"
            )
        }
        return ReviewConnection(nodes, hasNextPage, total.toInt())
    }

    /**
     * `averageRating`: average stars of **all** visible reviews (FIXED Q4, docs/fixes/review.md: the original
     * averaged only the first 25 reviews by id), one aggregate query; `f32` average emitted as `f64` like the
     * original; null without visible reviews or on a database error.
     */
    suspend fun averageRating(filter: Filter): Double? {
        val (count, sum) = try {
            store.visibleRatingStats(filter.where, filter.value)
        } catch (e: Exception) {
            return null
        }
        if (count == 0L) return null
        return (sum.toFloat() / count.toFloat()).toDouble()
    }

    // ---------------------------------------------------------------- mutations (authorization in Mutation)

    /** `create_review` after authorization (§5.1). */
    suspend fun createReview(input: CreateReviewInput): Review {
        if (!store.productVariantExists(input.productVariantId)) {
            throw GraphQLErrorException(
                "Product variant with the UUID: `${input.productVariantId}` is not present in the system."
            )
        }
        if (!store.userExists(input.userId)) {
            throw GraphQLErrorException("$USER_TYPE with UUID: `${input.userId}` not found.")
        }
        val productId = store.findProductVariantProductId(input.productVariantId)
            ?: throw GraphQLErrorException("$PRODUCT_VARIANT_TYPE with UUID: `${input.productVariantId}` not found.")
        val now = RustDateTime.now()
        val row = ReviewRow(
            id = UUID.randomUUID(),
            userId = input.userId,
            productVariantId = input.productVariantId,
            productVariantProductId = productId,
            body = input.body,
            rating = input.rating.stored,
            createdAt = now,
            lastUpdatedAt = now,
            isVisible = input.isVisible ?: true,
        )
        if (store.findReviewOfUser(input.userId, input.productVariantId) != null) {
            throw duplicate(input)
        }
        try {
            store.insertReview(row)
        } catch (e: DataIntegrityViolationException) {
            // FIXED: unique (user_id, product_variant_id) index closes the check-then-insert race
            throw duplicate(input)
        } catch (e: Exception) {
            throw GraphQLErrorException("Adding review failed in MongoDB.")
        }
        return loadReview(row.id)
    }

    private fun duplicate(input: CreateReviewInput) = GraphQLErrorException(
        "User of UUID: `${input.userId}` has already written a review for product variant of UUID: " +
            "`${input.productVariantId}`."
    )

    /** Loads the owner of a review for authorization (`query_object`, not-found first). */
    suspend fun loadForAuthorization(id: UUID): Review = loadReview(id)

    /** `update_review` after loading + authorization (§5.2); `now` was taken before loading. */
    suspend fun updateReview(input: UpdateReviewInput, now: java.time.Instant): Review {
        input.body?.let { update(input.id, "body", it, now, "body") }
        input.rating?.let { update(input.id, "rating", it.stored, now, "rating") }
        input.isVisible?.let { update(input.id, "is_visible", it, now, "visibility") }
        return loadReview(input.id)
    }

    private suspend fun update(id: UUID, column: String, value: Any, now: java.time.Instant, what: String) {
        try {
            store.updateReviewColumn(id, column, value, now)
        } catch (e: Exception) {
            throw GraphQLErrorException("Updating $what of review of id: `$id` failed in MongoDB.")
        }
    }

    /** `delete_review` after loading + authorization (§5.3). */
    suspend fun deleteReview(id: UUID): Boolean {
        try {
            store.deleteReview(id)
        } catch (e: Exception) {
            throw GraphQLErrorException("Deleting review of id: `$id` failed in MongoDB.")
        }
        return true
    }

    // ---------------------------------------------------------------- replicas (entity resolvers, events)

    suspend fun userExists(id: UUID) = store.userExists(id)
    suspend fun productExists(id: UUID) = store.productExists(id)
    suspend fun productVariantExists(id: UUID) = store.productVariantExists(id)

    private fun ReviewRow.toReview(): Review? {
        val rating = Rating.fromStored(rating) ?: return null
        return Review(
            id = id,
            user = User(userId),
            productVariant = ProductVariant(productVariantId),
            body = body,
            rating = rating,
            createdAt = createdAt,
            lastUpdatedAt = lastUpdatedAt,
            isVisible = isVisible,
        )
    }

    companion object {
        const val REVIEW_TYPE = "misarch_review::graphql::model::review::Review"
        const val USER_TYPE = "misarch_review::graphql::model::user::User"
        const val PRODUCT_VARIANT_TYPE = "misarch_review::graphql::model::product_variant::ProductVariant"

        fun notFound(id: UUID) = GraphQLErrorException("$REVIEW_TYPE with UUID: `$id` not found.")
    }
}
