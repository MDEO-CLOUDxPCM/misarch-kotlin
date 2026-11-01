package org.misarch.review.graphql.input

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import org.misarch.review.graphql.model.Rating
import java.util.UUID

class UpdateReviewInput(
    @property:GraphQLDescription("UUID of review to update.")
    val id: UUID,
    @property:GraphQLDescription("Body of review to update.")
    val body: String?,
    @property:GraphQLDescription("Rating of review in 1-5 stars to update.")
    val rating: Rating?,
    @property:GraphQLDescription("Flag if review is visible.")
    val isVisible: Boolean?,
)
