package org.misarch.user.persistence.model

import org.misarch.common.persistence.BaseEntity
import org.misarch.user.event.model.UserDTO
import org.misarch.user.graphql.model.Gender
import org.misarch.user.graphql.model.Name
import org.misarch.user.graphql.model.User
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * User entity (table `UserEntity`)
 *
 * @property username the username of the user
 * @property firstName the first name of the user
 * @property lastName the last name of the user
 * @property birthday the birthday of the user (stored in a TIMESTAMPTZ column)
 * @property gender the gender of the user (enum name)
 * @property dateJoined the date the user joined
 * @property id the id of the user
 */
@Table
class UserEntity(
    var username: String,
    var firstName: String,
    var lastName: String,
    var birthday: LocalDate?,
    var gender: Gender?,
    val dateJoined: OffsetDateTime,
    @Id
    override val id: UUID? = null
) : BaseEntity<User> {

    override fun toDTO(): User {
        return User(
            id = id!!,
            username = username,
            name = Name(
                firstName = firstName,
                lastName = lastName,
            ),
            birthday = birthday,
            gender = gender,
            dateJoined = dateJoined
        )
    }

    /**
     * Converts the entity to the `user/user/created` event payload
     *
     * @return the event DTO
     */
    fun toEventDTO(): UserDTO {
        return UserDTO(
            id = id!!,
            username = username,
            firstName = firstName,
            lastName = lastName,
            dateJoined = dateJoined
        )
    }

}
