package org.misarch.media.graphql.model.connection

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.ShareableDirective
import org.misarch.common.graphql.connection.PagingValidation
import org.misarch.media.graphql.model.Media
import org.misarch.media.service.MediaObject

/**
 * A page of the media files (the listing of the store, there is no database).
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
) {

    companion object {
        /**
         * The page of [mediaObjects] after [skip] items with at most [first] items (`first: 0` is an empty page,
         * negative arguments are errors).
         */
        fun of(mediaObjects: List<MediaObject>, first: Int?, skip: Int?): MediaConnection {
            PagingValidation.NON_NEGATIVE.check((skip ?: 0).toLong(), first?.toLong())
            val offset = skip ?: 0
            val page = mediaObjects.drop(offset).take(first ?: Int.MAX_VALUE)
            return MediaConnection(page.map { Media(it.id) }, mediaObjects.size > offset + page.size, mediaObjects.size)
        }
    }
}
