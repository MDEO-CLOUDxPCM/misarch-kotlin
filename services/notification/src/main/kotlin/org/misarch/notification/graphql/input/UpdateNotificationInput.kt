package org.misarch.notification.graphql.input

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import java.util.UUID

@GraphQLDescription("Input for the updateNotification mutation")
class UpdateNotificationInput(
    @property:GraphQLDescription("id of the notification to update")
    val id: UUID,
    @property:GraphQLDescription("mark the notification as read/unread")
    val isRead: Boolean,
)
