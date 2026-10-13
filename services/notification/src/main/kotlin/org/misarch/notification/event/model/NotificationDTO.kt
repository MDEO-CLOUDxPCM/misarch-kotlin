package org.misarch.notification.event.model

import org.misarch.notification.graphql.input.NotificationInput
import java.util.UUID

/**
 * Notification DTO used for events
 *
 * @property title title of the notification
 * @property body body of the notification
 * @property userId id of the user the notification was sent to
 */
data class NotificationDTO(
    override val title: String,
    override val body: String,
    override val userId: UUID,
) : NotificationInput
