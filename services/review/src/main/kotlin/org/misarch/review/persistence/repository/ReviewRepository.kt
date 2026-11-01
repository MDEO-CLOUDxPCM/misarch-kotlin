package org.misarch.review.persistence.repository

import org.misarch.review.persistence.model.ReviewEntity
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.UUID

/**
 * Repository for [ReviewEntity]s
 */
interface ReviewRepository : CoroutineCrudRepository<ReviewEntity, UUID> {

    /**
     * Whether the user has already reviewed the product variant
     */
    suspend fun existsByUserIdAndProductVariantId(userId: UUID, productVariantId: UUID): Boolean

    /**
     * The average number of stars of the visible reviews of a product, null without visible reviews
     */
    @Query("SELECT AVG(stars::float8) FROM ReviewEntity WHERE productId = :productId AND isVisible")
    suspend fun findAverageRatingByProductId(productId: UUID): Double?

    /**
     * The average number of stars of the visible reviews of a product variant, null without visible reviews
     */
    @Query("SELECT AVG(stars::float8) FROM ReviewEntity WHERE productVariantId = :productVariantId AND isVisible")
    suspend fun findAverageRatingByProductVariantId(productVariantId: UUID): Double?

}
