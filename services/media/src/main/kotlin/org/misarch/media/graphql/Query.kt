package org.misarch.media.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.server.operations.Query
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.graphql.authorizedUser
import org.misarch.media.graphql.model.connection.MediaConnection
import org.misarch.media.service.MediaService
import org.springframework.stereotype.Component

/**
 * Defines GraphQL queries
 *
 * @property mediaService lists the media files
 */
@Component
class Query(
    private val mediaService: MediaService
) : Query {

    @GraphQLDescription("Returns all medias of the service.")
    suspend fun getMedias(
        @GraphQLDescription("Describes that the `first` N medias should be retrieved.")
        first: Int? = null,
        @GraphQLDescription("Describes how many medias should be skipped at the beginning.")
        skip: Int? = null,
        dfe: DataFetchingEnvironment,
    ): MediaConnection {
        dfe.authorizedUser.checkIsEmployee()
        return mediaService.getMedias(first, skip)
    }

}
