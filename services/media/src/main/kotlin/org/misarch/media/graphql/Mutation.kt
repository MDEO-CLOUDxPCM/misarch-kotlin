package org.misarch.media.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.server.operations.Mutation
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.rust.RustUpload
import org.misarch.common.rust.rustAuthorizeUser
import org.misarch.media.service.MediaService
import org.springframework.stereotype.Component
import java.util.UUID

/** Media mutations (original `graphql/mutation.rs`). */
@Component
class Mutation(private val mediaService: MediaService) : Mutation {

    @GraphQLDescription(
        "Uploads a media to MinIO.\n\nSaves the file under a new UUID with its suitable file extension in MinIO.\n" +
            "Sends an event when a media file was uploaded."
    )
    suspend fun uploadMedia(
        @GraphQLDescription("Media file to upload.")
        mediaFile: RustUpload,
        dfe: DataFetchingEnvironment,
    ): UUID {
        dfe.rustAuthorizeUser(null)
        return mediaService.uploadMedia(mediaFile)
    }
}
