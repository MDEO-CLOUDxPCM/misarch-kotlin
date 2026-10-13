package org.misarch.discount.persistence.model

import org.misarch.common.persistence.BaseEntity
import org.misarch.discount.graphql.model.User
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.util.*

/**
 * Entity for users
 *
 * @property id unique identifier of the user
 */
@Table
class UserEntity(
    @Id
    override val id: UUID
) : BaseEntity<User> {

    override fun toDTO(): User {
        return User(
            id = id
        )
    }

}
