package org.misarch.review.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import java.time.Instant
import java.util.UUID

/** The review of a user. */
@GraphQLDescription("The review of a user.")
class Review(
    @property:GraphQLDescription("review UUID.")
    val id: UUID,
    @property:GraphQLDescription("User.")
    val user: User,
    @property:GraphQLDescription("Product variant that review is about.")
    val productVariant: ProductVariant,
    @property:GraphQLDescription("Body of review.")
    val body: String,
    @property:GraphQLDescription("Rating of review in 1-5 stars.")
    val rating: Rating,
    @property:GraphQLDescription("Timestamp when review was created.")
    val createdAt: Instant,
    @property:GraphQLDescription("Timestamp when review was created.")
    val lastUpdatedAt: Instant,
    @property:GraphQLDescription("Flag if review is visible,")
    val isVisible: Boolean,
)
