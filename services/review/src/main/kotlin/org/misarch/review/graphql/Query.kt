package org.misarch.review.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.server.operations.Query
import org.misarch.review.graphql.model.Review
import org.misarch.review.graphql.model.ReviewConnection
import org.misarch.review.graphql.model.ReviewOrderInput
import org.misarch.review.service.ReviewService
import org.springframework.stereotype.Component
import java.util.UUID

/** Review queries (original `graphql/query.rs`); entity resolvers are in `federation/`. No authorization. */
@Component
class Query(private val reviewService: ReviewService) : Query {

    @GraphQLDescription("Retrieves all reviews.")
    suspend fun reviews(
        @GraphQLDescription("Describes that the `first` N reviews should be retrieved.")
        first: Int? = null,
        @GraphQLDescription("Describes how many reviews should be skipped at the beginning.")
        skip: Int? = null,
        @GraphQLDescription("Specifies the order in which reviews are retrieved.")
        orderBy: ReviewOrderInput? = null,
    ): ReviewConnection = reviewService.connection(ReviewService.Filter.All, first, skip, orderBy)

    @GraphQLDescription("Retrieves review of specific UUID.")
    suspend fun review(
        @GraphQLDescription("UUID of review to retrieve.")
        id: UUID,
    ): Review? = reviewService.findReview(id)
}
