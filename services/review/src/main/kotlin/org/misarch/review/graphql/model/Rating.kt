package org.misarch.review.graphql.model

/**
 * Rating of a review (stored as the constant name; the table's generated column `stars` holds the number)
 */
enum class Rating {
    ONE_STARS,
    TWO_STARS,
    THREE_STARS,
    FOUR_STARS,
    FIVE_STARS
}
