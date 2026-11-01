package org.misarch.review.service

import org.misarch.common.graphql.AuthorizedUser
import org.misarch.common.persistence.findByIdOrThrow
import org.misarch.review.graphql.input.CreateReviewInput
import org.misarch.review.graphql.input.UpdateReviewInput
import org.misarch.review.persistence.model.ReviewEntity
import org.misarch.review.persistence.repository.ProductVariantRepository
import org.misarch.review.persistence.repository.ReviewRepository
import org.misarch.review.persistence.repository.UserRepository
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Service for [ReviewEntity]s
 *
 * @param repository the repository for [ReviewEntity]s
 * @param userRepository the repository for the replicated users
 * @param productVariantRepository the repository for the replicated product variants
 */
@Service
class ReviewService(
    private val repository: ReviewRepository,
    private val userRepository: UserRepository,
    private val productVariantRepository: ProductVariantRepository,
) {

    /**
     * Creates a review; a user can review a product variant only once (also enforced by a unique index)
     *
     * @param input the review to create
     * @return the created review
     */
    @Transactional
    suspend fun createReview(input: CreateReviewInput): ReviewEntity {
        val productVariant = productVariantRepository.findById(input.productVariantId)
            ?: throw IllegalArgumentException("Product variant with id ${input.productVariantId} does not exist")
        if (!userRepository.existsById(input.userId)) {
            throw IllegalArgumentException("User with id ${input.userId} does not exist")
        }
        if (repository.existsByUserIdAndProductVariantId(input.userId, input.productVariantId)) {
            throw alreadyReviewed(input)
        }
        val now = OffsetDateTime.now()
        val review = ReviewEntity(
            userId = input.userId,
            productVariantId = input.productVariantId,
            productId = productVariant.productId,
            body = input.body,
            rating = input.rating,
            createdAt = now,
            lastUpdatedAt = now,
            isVisible = input.isVisible ?: true,
            id = null
        )
        return try {
            repository.save(review)
        } catch (e: DuplicateKeyException) {
            throw alreadyReviewed(input)
        }
    }

    /**
     * Updates the given fields of a review (also checks permissions); `lastUpdatedAt` changes if any field is given
     *
     * @param input the review and the fields to update
     * @param authorizedUser the user executing the mutation
     * @return the updated review
     */
    @Transactional
    suspend fun updateReview(input: UpdateReviewInput, authorizedUser: AuthorizedUser): ReviewEntity {
        val review = findOwnReview(input.id, authorizedUser)
        if (input.body == null && input.rating == null && input.isVisible == null) {
            return review
        }
        input.body?.let { review.body = it }
        input.rating?.let { review.rating = it }
        input.isVisible?.let { review.isVisible = it }
        review.lastUpdatedAt = OffsetDateTime.now()
        return repository.save(review)
    }

    /**
     * Deletes a review (also checks permissions)
     *
     * @param id the id of the review
     * @param authorizedUser the user executing the mutation
     */
    @Transactional
    suspend fun deleteReview(id: UUID, authorizedUser: AuthorizedUser) {
        repository.delete(findOwnReview(id, authorizedUser))
    }

    /**
     * The review [id]; only its author, employees and admins may change it
     */
    private suspend fun findOwnReview(id: UUID, authorizedUser: AuthorizedUser): ReviewEntity {
        val review = repository.findByIdOrThrow(id)
        if (review.userId != authorizedUser.id) {
            authorizedUser.checkIsEmployee()
        }
        return review
    }

    private fun alreadyReviewed(input: CreateReviewInput) = IllegalArgumentException(
        "User with id ${input.userId} has already reviewed product variant with id ${input.productVariantId}"
    )

}
