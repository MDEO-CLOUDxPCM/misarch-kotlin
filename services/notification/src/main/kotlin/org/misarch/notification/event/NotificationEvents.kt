package org.misarch.notification.event

/**
 * Constants for notification event topics used in the application
 */
object NotificationEvents {
    /**
     * Topic for notification creation events (create a new notification)
     */
    const val NOTIFICATION_CREATE = "notification/notification/create"

    /**
     * Topic for user creation events (a user has been created)
     */
    const val USER_CREATED = "user/user/created"

    /**
     * Name of the pubsub component
     */
    const val PUBSUB_NAME = "pubsub"
}
