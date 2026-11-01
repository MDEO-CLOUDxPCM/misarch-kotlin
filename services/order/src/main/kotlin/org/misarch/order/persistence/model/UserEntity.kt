package org.misarch.order.persistence.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

/**
 * Entity for users (replica, fed by `user/user/created`)
 *
 * @property id unique identifier of the user
 */
@Table
class UserEntity(
    @Id
    val id: UUID
)
