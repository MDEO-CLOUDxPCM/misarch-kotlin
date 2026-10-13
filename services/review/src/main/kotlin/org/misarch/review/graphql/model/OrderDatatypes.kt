package org.misarch.review.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription

/** `OrderDirection` of the original (`order_datatypes.rs`). */
@GraphQLDescription("GraphQL order direction.")
enum class OrderDirection(val sql: String) {
    @GraphQLDescription("Ascending order direction.")
    ASC("ASC"),

    @GraphQLDescription("Descending order direction.")
    DESC("DESC"),
}

/**
 * `ReviewOrderField` of the original. [orderBy] reproduces `as_str()` (Mongo sort key) plus the `_id: -1` tiebreak
 * that mongodb-cursor-pagination appends for every key except `_id` (docs/specs/review.md §5.5, Q3).
 */
@GraphQLDescription("Describes the fields that a review can be ordered by.")
enum class ReviewOrderField {
    @GraphQLDescription("Orders by \"id\".")
    ID,

    /** Mongo key `user` (embedded `{_id}`). */
    @GraphQLDescription("Orders by \"user_id\".")
    USER_ID,

    /** Mongo key `product_variant` (embedded `{_id, product_id}`). */
    @GraphQLDescription("Orders by \"product_variant\".")
    PRODUCT_VARIANT,

    /** Mongo key `rating`: the stored string, compared byte-wise (lexicographic, not numeric). */
    @GraphQLDescription("Orders by \"rating\".")
    RATING,

    /** QUIRK: Mongo key `last_updated_at`. */
    @GraphQLDescription("Orders by \"created_at\".")
    CREATED_AT;

    /** SQL ORDER BY clause for [direction]. */
    fun orderBy(direction: OrderDirection): String {
        val d = direction.sql
        return when (this) {
            ID -> "id $d"
            USER_ID -> "user_id $d, id DESC"
            PRODUCT_VARIANT -> "product_variant_id $d, product_variant_product_id $d, id DESC"
            RATING -> "rating COLLATE \"C\" $d, id DESC"
            CREATED_AT -> "last_updated_at $d, id DESC"
        }
    }
}

/** Specifies the order of reviews. */
@GraphQLDescription("Specifies the order of reviews.")
class ReviewOrderInput(
    @property:GraphQLDescription("Order direction of reviews.")
    val direction: OrderDirection? = null,
    @property:GraphQLDescription("Field that reviews should be ordered by.")
    val field: ReviewOrderField? = null,
)
