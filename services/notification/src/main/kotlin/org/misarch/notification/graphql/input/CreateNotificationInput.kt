package org.misarch.notification.graphql.input

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import java.util.UUID

@GraphQLDescription("Input for the createNotification mutation")
class CreateNotificationInput(
    @property:GraphQLDescription("title of the notification to create")
    override val title: String,
    @property:GraphQLDescription("body of the notification to create")
    override val body: String,
    @property:GraphQLDescription("id of the user the notification should be sent to")
    override val userId: UUID
) : NotificationInput
