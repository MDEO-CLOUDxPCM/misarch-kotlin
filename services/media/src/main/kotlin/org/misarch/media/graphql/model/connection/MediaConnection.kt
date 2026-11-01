package org.misarch.media.graphql.model.connection

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.ShareableDirective
import org.misarch.media.graphql.model.Media

/**
 * A page of the media files (the listing of the store, there is no database)
 */
@GraphQLDescription("A connection of medias.")
@ShareableDirective
class MediaConnection(
    @property:GraphQLDescription("The resulting entities.")
    val nodes: List<Media>,
    @property:GraphQLDescription("Whether this connection has a next page.")
    val hasNextPage: Boolean,
    @property:GraphQLDescription("The total amount of items in this connection.")
    val totalCount: Int,
)
