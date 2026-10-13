package org.misarch.notification.graphql.input

import java.util.UUID

/**
 * Notification input used to create a notification
 */
interface NotificationInput {
    /**
     * title of the notification
     */
    val title: String

    /**
     * body of the notification
     */
    val body: String

    /**
     * id of the user the notification was sent to
     */
    val userId: UUID
}
