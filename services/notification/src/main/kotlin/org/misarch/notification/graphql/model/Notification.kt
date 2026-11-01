package org.misarch.notification.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.graphql.load
import org.misarch.notification.graphql.dataloader.UserDataLoader
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CompletableFuture

@GraphQLDescription("A notification.")
@KeyDirective(fields = FieldSet("id"))
class Notification(
    id: UUID,
    @property:GraphQLDescription("The title of the notification")
    val title: String,
    @property:GraphQLDescription("The content of the notification")
    val body: String,
    @property:GraphQLDescription("The date the notification was sent")
    val dateSent: OffsetDateTime,
    @property:GraphQLDescription("The date the notification was read")
    val dateRead: OffsetDateTime?,
    internal val userId: UUID
) : Node(id) {

    @GraphQLDescription("Whether the notification has been read")
    val isRead get() = dateRead != null

    @GraphQLDescription("The user this notification was sent to.")
    fun user(
        dfe: DataFetchingEnvironment
    ): CompletableFuture<User> {
        return dfe.load(UserDataLoader::class, userId)
    }

}
