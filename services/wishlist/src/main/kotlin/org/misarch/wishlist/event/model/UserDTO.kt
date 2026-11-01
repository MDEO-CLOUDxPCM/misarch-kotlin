package org.misarch.wishlist.event.model

import java.util.UUID

/**
 * User DTO used for events (only the fields this service reads)
 *
 * @property id id of the user
 */
data class UserDTO(
    val id: UUID
)
