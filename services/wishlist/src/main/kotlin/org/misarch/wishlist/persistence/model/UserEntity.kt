package org.misarch.wishlist.persistence.model

import org.misarch.common.persistence.BaseEntity
import org.misarch.wishlist.graphql.model.User
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

/**
 * Entity for users (replica of the user service's users: only the id, fed by `user/user/created`)
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
