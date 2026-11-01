package org.misarch.review.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.server.operations.Mutation
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.graphql.authorizedUser
import org.misarch.review.graphql.input.CreateReviewInput
import org.misarch.review.graphql.input.UpdateReviewInput
import org.misarch.review.graphql.model.Review
import org.misarch.review.service.ReviewService
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Defines GraphQL mutations
 */
@Component
class Mutation(
    private val reviewService: ReviewService
) : Mutation {

    @GraphQLDescription("Adds a review for a user and a product variant with a content, rating and visibility.")
    suspend fun createReview(
        @GraphQLDescription("CreateReviewInput")
        input: CreateReviewInput,
        dfe: DataFetchingEnvironment,
    ): Review {
        val authorizedUser = dfe.authorizedUser
        if (input.userId != authorizedUser.id) {
            authorizedUser.checkIsEmployee()
        }
        return reviewService.createReview(input).toDTO()
    }

    @GraphQLDescription("Updates a specific review referenced with an UUID.")
    suspend fun updateReview(
        @GraphQLDescription("UpdateReviewInput")
        input: UpdateReviewInput,
        dfe: DataFetchingEnvironment,
    ): Review {
        return reviewService.updateReview(input, dfe.authorizedUser).toDTO()
    }

    @GraphQLDescription("Deletes review of UUID.")
    suspend fun deleteReview(
        @GraphQLDescription("UUID of review to delete.")
        id: UUID,
        dfe: DataFetchingEnvironment,
    ): Boolean {
        reviewService.deleteReview(id, dfe.authorizedUser)
        return true
    }

}
