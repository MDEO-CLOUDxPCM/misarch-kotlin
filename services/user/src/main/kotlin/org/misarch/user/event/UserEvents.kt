package org.misarch.user.event

/**
 * Constants for user event topics used in the application
 */
object UserEvents {
    /**
     * Topic for user creation events (a user has been created)
     */
    const val USER_CREATED = "user/user/created"

    /**
     * Topic for create user events (a user should be created)
     */
    const val USER_CREATE = "user/user/create"
}
