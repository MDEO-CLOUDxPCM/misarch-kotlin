package org.misarch.review.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.annotations.GraphQLIgnore
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import org.misarch.common.persistence.Condition
import org.misarch.review.graphql.model.connection.ReviewConnection
import org.misarch.review.graphql.model.connection.ReviewOrderInput
import org.misarch.review.persistence.repository.ReviewRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import java.util.UUID

@KeyDirective(fields = FieldSet("id"))
class ProductVariant(
    @property:GraphQLDescription("Product variant UUID.")
    val id: UUID,
) {

    @GraphQLDescription("Retrieves reviews of product variant.")
    fun reviews(
        @GraphQLDescription("Describes that the `first` N reviews should be retrieved.")
        first: Int? = null,
        @GraphQLDescription("Describes how many reviews should be skipped at the beginning.")
        skip: Int? = null,
        @GraphQLDescription("Specifies the order in which reviews are retrieved.")
        orderBy: ReviewOrderInput? = null,
        @GraphQLIgnore
        @Autowired
        entityTemplate: R2dbcEntityTemplate,
    ): ReviewConnection {
        return ReviewConnection(first, skip, Condition.eq("productVariantId", id), orderBy, entityTemplate)
    }

    @GraphQLDescription("Retrieves average rating of product variant.")
    suspend fun averageRating(
        @GraphQLIgnore
        @Autowired
        reviewRepository: ReviewRepository,
    ): Double? {
        return reviewRepository.findAverageRatingByProductVariantId(id)
    }

}
