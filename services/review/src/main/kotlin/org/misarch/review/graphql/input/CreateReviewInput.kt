package org.misarch.review.graphql.input

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import org.misarch.review.graphql.model.Rating
import java.util.UUID

class CreateReviewInput(
    @property:GraphQLDescription("UUID of user owning the review.")
    val userId: UUID,
    @property:GraphQLDescription("UUID of product variant in review.")
    val productVariantId: UUID,
    @property:GraphQLDescription("Body of review.")
    val body: String,
    @property:GraphQLDescription("Rating of review in 1-5 stars.")
    val rating: Rating,
    @property:GraphQLDescription("Flag if review is visible, by default set to true.")
    val isVisible: Boolean? = null,
)
