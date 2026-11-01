package org.misarch.review.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.annotations.GraphQLIgnore
import com.expediagroup.graphql.server.operations.Query
import org.misarch.review.graphql.model.Review
import org.misarch.review.graphql.model.connection.ReviewConnection
import org.misarch.review.graphql.model.connection.ReviewOrder
import org.misarch.review.persistence.repository.ReviewRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Defines GraphQL queries (reviews are public)
 *
 * @property reviewRepository repository for reviews
 */
@Component
class Query(
    private val reviewRepository: ReviewRepository,
) : Query {

    @GraphQLDescription("Retrieves all reviews.")
    fun reviews(
        @GraphQLDescription("Describes that the `first` N reviews should be retrieved.")
        first: Int? = null,
        @GraphQLDescription("Describes how many reviews should be skipped at the beginning.")
        skip: Int? = null,
        @GraphQLDescription("Specifies the order in which reviews are retrieved.")
        orderBy: ReviewOrder? = null,
        @GraphQLIgnore
        @Autowired
        entityTemplate: R2dbcEntityTemplate,
    ): ReviewConnection {
        return ReviewConnection(first, skip, null, orderBy, entityTemplate)
    }

    @GraphQLDescription("Retrieves review of specific UUID.")
    suspend fun review(
        @GraphQLDescription("UUID of review to retrieve.")
        id: UUID,
    ): Review? {
        return reviewRepository.findById(id)?.toDTO()
    }

}
