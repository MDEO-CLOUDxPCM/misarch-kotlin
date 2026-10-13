package org.misarch.review.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.annotations.GraphQLIgnore
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import org.misarch.review.service.ReviewService
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/** Foreign product with the review fields contributed by this service. */
@KeyDirective(fields = FieldSet("id"))
class Product(
    @property:GraphQLDescription("UUID of the product.")
    val id: UUID,
) {
    @GraphQLDescription("Retrieves reviews of product.")
    suspend fun reviews(
        @GraphQLDescription("Describes that the `first` N reviews should be retrieved.")
        first: Int? = null,
        @GraphQLDescription("Describes how many reviews should be skipped at the beginning.")
        skip: Int? = null,
        @GraphQLDescription("Specifies the order in which reviews are retrieved.")
        orderBy: ReviewOrderInput? = null,
        @GraphQLIgnore @Autowired
        reviewService: ReviewService,
    ): ReviewConnection = reviewService.connection(ReviewService.Filter.Product(id), first, skip, orderBy)

    @GraphQLDescription("Retrieves average rating of product.")
    suspend fun averageRating(
        @GraphQLIgnore @Autowired
        reviewService: ReviewService,
    ): Double? = reviewService.averageRating(ReviewService.Filter.Product(id))
}
