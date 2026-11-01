package org.misarch.review.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.server.operations.Query
import org.misarch.review.graphql.model.Review
import org.misarch.review.graphql.model.connection.ReviewConnection
import org.misarch.review.graphql.model.connection.ReviewOrder
import org.misarch.review.service.ReviewService
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Defines GraphQL queries (reviews are public)
 *
 * @property reviewService service for reviews
 * @property entityTemplate runs the connection queries
 */
@Component
class Query(
    private val reviewService: ReviewService,
    private val entityTemplate: R2dbcEntityTemplate
) : Query {

    @GraphQLDescription("Retrieves all reviews.")
    fun reviews(
        @GraphQLDescription("Describes that the `first` N reviews should be retrieved.")
        first: Int? = null,
        @GraphQLDescription("Describes how many reviews should be skipped at the beginning.")
        skip: Int? = null,
        @GraphQLDescription("Specifies the order in which reviews are retrieved.")
        orderBy: ReviewOrder? = null
    ): ReviewConnection {
        return ReviewConnection(first, skip, null, orderBy, entityTemplate)
    }

    @GraphQLDescription("Retrieves review of specific UUID.")
    suspend fun review(
        @GraphQLDescription("UUID of review to retrieve.")
        id: UUID
    ): Review? {
        return reviewService.findReview(id)?.toDTO()
    }

}
