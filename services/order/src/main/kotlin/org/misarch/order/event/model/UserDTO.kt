package org.misarch.order.event.model

import java.util.UUID

/**
 * DTO for a created user (only the id is used)
 *
 * @property id unique identifier of the user
 */
data class UserDTO(
    val id: UUID
)
