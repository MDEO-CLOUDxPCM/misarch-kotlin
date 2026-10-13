package org.misarch.review.graphql.model

/**
 * Rating of a review. [stored] is the serde name written to the database (on insert and, FIXED, on update: the
 * original's hand-written `Rating::to_string()` wrote `FourStarst` on update (QUIRK Q1), which made the review
 * undeserializable; docs/fixes/review.md).
 */
enum class Rating(val stars: Int, val stored: String) {
    ONE_STARS(1, "OneStars"),
    TWO_STARS(2, "TwoStars"),
    THREE_STARS(3, "ThreeStars"),
    FOUR_STARS(4, "FourStars"),
    FIVE_STARS(5, "FiveStars");

    companion object {
        /** serde deserialization of the stored string; null = not deserializable. */
        fun fromStored(token: String): Rating? = entries.firstOrNull { it.stored == token }

        /** SQL expression for the number of stars of the stored `rating` column (numeric order, average). */
        val STARS_SQL: String = "CASE rating " +
            entries.joinToString(" ") { "WHEN '${it.stored}' THEN ${it.stars}" } + " END"
    }
}
