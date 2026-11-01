package org.misarch.invoice.persistence.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

/**
 * Entity for users (replica, fed by `user/user/created`)
 *
 * @property firstName the first name of the user
 * @property lastName the last name of the user
 * @property id unique identifier of the user
 */
@Table
class UserEntity(
    val firstName: String,
    val lastName: String,
    @Id
    val id: UUID
)
