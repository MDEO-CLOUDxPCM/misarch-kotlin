package org.misarch.media.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.annotations.GraphQLIgnore
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import org.misarch.media.service.MediaService
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

@GraphQLDescription("Media object with associated path and id.")
@KeyDirective(fields = FieldSet("id"))
class Media(
    @property:GraphQLDescription("Media UUID.")
    val id: UUID,
) {

    @GraphQLDescription("Pre-signed path for the media.")
    suspend fun path(
        @GraphQLIgnore
        @Autowired
        mediaService: MediaService,
    ): String = mediaService.presignedPath(id)

}
