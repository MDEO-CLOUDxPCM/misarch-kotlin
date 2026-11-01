package org.misarch.invoice.event.model

import java.util.UUID

/**
 * DTO of a created user
 *
 * @property id unique identifier of the user
 * @property firstName the first name of the user
 * @property lastName the last name of the user
 */
data class UserDTO(
    val id: UUID,
    val firstName: String,
    val lastName: String
)
