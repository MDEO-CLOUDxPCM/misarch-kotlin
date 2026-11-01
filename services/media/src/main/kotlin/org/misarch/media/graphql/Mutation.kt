package org.misarch.media.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.server.operations.Mutation
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.graphql.Upload
import org.misarch.common.graphql.authorizedUser
import org.misarch.media.service.MediaService
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Defines GraphQL mutations
 */
@Component
class Mutation(
    private val mediaService: MediaService
) : Mutation {

    @GraphQLDescription(
        "Uploads a media to MinIO.\n\nSaves the file under a new UUID with its suitable file extension in MinIO.\n" +
            "Sends an event when a media file was uploaded."
    )
    suspend fun uploadMedia(
        @GraphQLDescription("Media file to upload.")
        mediaFile: Upload,
        dfe: DataFetchingEnvironment,
    ): UUID {
        dfe.authorizedUser.checkIsEmployee()
        return mediaService.uploadMedia(mediaFile)
    }

}
