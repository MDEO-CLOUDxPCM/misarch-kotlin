package org.misarch.review.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.annotations.GraphQLIgnore
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import org.misarch.review.service.ReviewService
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/** Type of a user owning reviews. */
@KeyDirective(fields = FieldSet("id"))
@GraphQLDescription("Type of a user owning reviews.")
class User(
    @property:GraphQLDescription("UUID of the user.")
    val id: UUID,
) {
    @GraphQLDescription("Retrieves reviews of user.")
    suspend fun reviews(
        @GraphQLDescription("Describes that the `first` N reviews should be retrieved.")
        first: Int? = null,
        @GraphQLDescription("Describes how many reviews should be skipped at the beginning.")
        skip: Int? = null,
        @GraphQLDescription("Specifies the order in which reviews are retrieved.")
        orderBy: ReviewOrderInput? = null,
        @GraphQLIgnore @Autowired
        reviewService: ReviewService,
    ): ReviewConnection = reviewService.connection(ReviewService.Filter.User(id), first, skip, orderBy)
}
