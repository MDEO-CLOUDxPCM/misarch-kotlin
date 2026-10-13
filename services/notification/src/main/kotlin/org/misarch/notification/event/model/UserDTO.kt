package org.misarch.notification.event.model

import java.util.UUID

/**
 * User DTO used for events (only the id of `user/user/created` is used)
 *
 * @property id id of the user
 */
data class UserDTO(
    val id: UUID
)
