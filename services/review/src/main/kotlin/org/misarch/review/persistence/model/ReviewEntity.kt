package org.misarch.review.persistence.model

import org.misarch.common.persistence.BaseEntity
import org.misarch.review.graphql.model.ProductVariant
import org.misarch.review.graphql.model.Rating
import org.misarch.review.graphql.model.Review
import org.misarch.review.graphql.model.User
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Entity for reviews. The table also has the generated column `stars` (number of stars of [rating]), used to order
 * by rating and for the average rating.
 *
 * @property userId id of the user who wrote the review
 * @property productVariantId id of the reviewed product variant
 * @property productId id of the product of the reviewed product variant
 * @property body text of the review
 * @property rating rating in stars
 * @property createdAt when the review was created
 * @property lastUpdatedAt when the review was last changed
 * @property isVisible whether the review is visible
 * @property id unique identifier of the review
 */
@Table
class ReviewEntity(
    val userId: UUID,
    val productVariantId: UUID,
    val productId: UUID,
    var body: String,
    var rating: Rating,
    val createdAt: OffsetDateTime,
    var lastUpdatedAt: OffsetDateTime,
    var isVisible: Boolean,
    @Id
    override val id: UUID?
) : BaseEntity<Review> {

    override fun toDTO(): Review {
        return Review(
            id = id!!,
            user = User(userId),
            productVariant = ProductVariant(productVariantId),
            body = body,
            rating = rating,
            createdAt = createdAt,
            lastUpdatedAt = lastUpdatedAt,
            isVisible = isVisible
        )
    }

}
