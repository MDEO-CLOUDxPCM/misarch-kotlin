package org.misarch.review.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.ShareableDirective

/** A connection of reviews. */
@ShareableDirective
@GraphQLDescription("A connection of reviews.")
class ReviewConnection(
    @property:GraphQLDescription("The resulting entities.")
    val nodes: List<Review>,
    @property:GraphQLDescription("Whether this connection has a next page.")
    val hasNextPage: Boolean,
    @property:GraphQLDescription("The total amount of items in this connection.")
    val totalCount: Int,
)
