package org.misarch.notification.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.server.operations.Query
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.graphql.authorizedUser
import org.misarch.common.graphql.dataLoader
import org.misarch.notification.graphql.dataloader.NotificationDataLoader
import org.misarch.notification.graphql.model.Notification
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Defines GraphQL queries
 */
@Component
class Query : Query {

    @GraphQLDescription("Get a notification by id")
    fun notification(
        @GraphQLDescription("The id of the notification")
        id: UUID,
        dfe: DataFetchingEnvironment
    ): CompletableFuture<Notification> {
        // loads first, authorizes afterwards, like the original; an unknown id fails only this key (FIXED, the original
        // failed the whole data loader batch with a NullPointerException)
        return dfe.dataLoader<UUID, Notification>(NotificationDataLoader::class.simpleName!!).load(id).thenApply {
            val notification = it!!
            val authorizedUser = dfe.authorizedUser
            if (notification.userId != authorizedUser.id) {
                authorizedUser.checkIsEmployee()
            }
            notification
        }
    }

}
