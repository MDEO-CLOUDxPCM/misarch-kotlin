package org.misarch.review.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.annotations.GraphQLIgnore
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import org.misarch.common.persistence.Condition
import org.misarch.review.graphql.model.connection.ReviewConnection
import org.misarch.review.graphql.model.connection.ReviewOrder
import org.misarch.review.persistence.repository.ReviewRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import java.util.UUID

@KeyDirective(fields = FieldSet("id"))
class Product(
    @property:GraphQLDescription("UUID of the product.")
    val id: UUID,
) {

    @GraphQLDescription("Retrieves reviews of product.")
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
        return ReviewConnection(first, skip, Condition.eq("productId", id), orderBy, entityTemplate)
    }

    @GraphQLDescription("Retrieves average rating of product.")
    suspend fun averageRating(
        @GraphQLIgnore
        @Autowired
        reviewRepository: ReviewRepository,
    ): Double? {
        return reviewRepository.findAverageRatingByProductId(id)
    }

}
