package org.misarch.payment.event.model

import java.util.UUID

/**
 * A created user (only the id is used)
 *
 * @property id the id of the user
 */
data class UserDTO(val id: UUID)
