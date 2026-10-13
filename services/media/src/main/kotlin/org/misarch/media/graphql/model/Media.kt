package org.misarch.media.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.annotations.GraphQLIgnore
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import com.expediagroup.graphql.generator.federation.directives.ShareableDirective
import org.misarch.media.service.MediaService
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/** Media object with associated path and id. */
@KeyDirective(fields = FieldSet("id"))
@GraphQLDescription("Media object with associated path and id.")
class Media(
    @property:GraphQLDescription("Media UUID.")
    val id: UUID,
) {
    @GraphQLDescription("Pre-signed path for the media.")
    suspend fun path(
        @GraphQLIgnore @Autowired
        mediaService: MediaService,
    ): String = mediaService.presignedPath(id)
}

/** A connection of medias. */
@ShareableDirective
@GraphQLDescription("A connection of medias.")
class MediaConnection(
    @property:GraphQLDescription("The resulting entities.")
    val nodes: List<Media>,
    @property:GraphQLDescription("Whether this connection has a next page.")
    val hasNextPage: Boolean,
    @property:GraphQLDescription("The total amount of items in this connection.")
    val totalCount: Int,
)
