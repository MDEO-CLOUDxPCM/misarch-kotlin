package org.misarch.review.graphql.model

/**
 * Rating of a review. [stored] is the serde name written on insert; [updateToken] is what the original's hand-written
 * `Rating::to_string()` writes on update - with the typo `FourStarst` (QUIRK Q1, docs/specs/review.md §5.2).
 */
enum class Rating(val stars: Int, val stored: String, val updateToken: String) {
    ONE_STARS(1, "OneStars", "OneStars"),
    TWO_STARS(2, "TwoStars", "TwoStars"),
    THREE_STARS(3, "ThreeStars", "ThreeStars"),
    FOUR_STARS(4, "FourStars", "FourStarst"),
    FIVE_STARS(5, "FiveStars", "FiveStars");

    companion object {
        /** serde deserialization of the stored string; null = not deserializable (e.g. `FourStarst`). */
        fun fromStored(token: String): Rating? = entries.firstOrNull { it.stored == token }
    }
}
