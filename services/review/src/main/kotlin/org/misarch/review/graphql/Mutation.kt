package org.misarch.review.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.server.operations.Mutation
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.rust.RustDateTime
import org.misarch.common.rust.rustAuthorizeUser
import org.misarch.review.graphql.input.CreateReviewInput
import org.misarch.review.graphql.input.UpdateReviewInput
import org.misarch.review.graphql.model.Review
import org.misarch.review.service.ReviewService
import org.springframework.stereotype.Component
import java.util.UUID

/** Review mutations (original `graphql/mutation.rs`), incl. the authorization order of the original. */
@Component
class Mutation(private val reviewService: ReviewService) : Mutation {

    @GraphQLDescription("Adds a review for a user and a product variant with a content, rating and visibility.")
    suspend fun createReview(
        @GraphQLDescription("CreateReviewInput")
        input: CreateReviewInput,
        dfe: DataFetchingEnvironment,
    ): Review {
        dfe.rustAuthorizeUser(input.userId)
        return reviewService.createReview(input)
    }

    @GraphQLDescription("Updates a specific review referenced with an UUID.")
    suspend fun updateReview(
        @GraphQLDescription("UpdateReviewInput")
        input: UpdateReviewInput,
        dfe: DataFetchingEnvironment,
    ): Review {
        val now = RustDateTime.now()
        val review = reviewService.loadForAuthorization(input.id)
        dfe.rustAuthorizeUser(review.user.id)
        return reviewService.updateReview(input, now)
    }

    @GraphQLDescription("Deletes review of UUID.")
    suspend fun deleteReview(
        @GraphQLDescription("UUID of review to delete.")
        id: UUID,
        dfe: DataFetchingEnvironment,
    ): Boolean {
        val review = reviewService.loadForAuthorization(id)
        dfe.rustAuthorizeUser(review.user.id)
        return reviewService.deleteReview(id)
    }
}
