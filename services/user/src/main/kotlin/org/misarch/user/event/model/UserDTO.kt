package org.misarch.user.event.model

import java.time.OffsetDateTime
import java.util.UUID

/**
 * User DTO used for events
 *
 * @property id id of the user
 * @property username the username of the user
 * @property firstName the first name of the user
 * @property lastName the last name of the user
 * @property dateJoined the date the user joined
 */
data class UserDTO(
    val id: UUID,
    val username: String,
    val firstName: String,
    val lastName: String,
    val dateJoined: OffsetDateTime
)
