package org.misarch.review.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.ShareableDirective
import org.misarch.common.graphql.connection.ConnectionQuery
import org.misarch.review.persistence.ReviewRow

/**
 * A connection of reviews: each field is one statement of the [ConnectionQuery] (page / COUNT / further-row probe),
 * run only when the field is selected - the connection pattern of the Kotlin services (docs/PORTING.md §6).
 */
@ShareableDirective
@GraphQLDescription("A connection of reviews.")
class ReviewConnection(
    private val query: ConnectionQuery<ReviewRow>,
    private val toReview: (ReviewRow) -> Review,
) {
    @GraphQLDescription("The resulting entities.")
    suspend fun nodes(): List<Review> = query.nodes().map(toReview)

    @GraphQLDescription("Whether this connection has a next page.")
    suspend fun hasNextPage(): Boolean = query.hasNextPage()

    @GraphQLDescription("The total amount of items in this connection.")
    suspend fun totalCount(): Int = query.totalCount()
}
