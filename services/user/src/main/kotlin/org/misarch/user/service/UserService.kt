package org.misarch.user.service

import org.misarch.common.event.EventPublisher
import org.misarch.user.event.UserEvents
import org.misarch.user.event.model.CreateUserDTO
import org.misarch.user.graphql.ifPresent
import org.misarch.user.graphql.input.UpdateUserInput
import org.misarch.user.persistence.model.UserEntity
import org.misarch.user.persistence.repository.UserRepository
import org.springframework.stereotype.Service
import java.time.OffsetDateTime

/**
 * Service for [UserEntity]s
 *
 * @param repository the repository for [UserEntity]s
 * @property eventPublisher publisher for events
 */
@Service
class UserService(
    private val repository: UserRepository,
    private val eventPublisher: EventPublisher
) {

    /**
     * Creates a new user (from Keycloak's `user/user/create`) and publishes `user/user/created`.
     * Not transactional and not idempotent, like the original: a duplicate id fails on the primary key.
     *
     * @param createUserDTO defines the new user
     * @return the created user
     */
    suspend fun createUser(createUserDTO: CreateUserDTO): UserEntity {
        repository.createUser(
            id = createUserDTO.id,
            username = createUserDTO.username,
            firstName = createUserDTO.firstName,
            lastName = createUserDTO.lastName,
            dateJoined = OffsetDateTime.now()
        )
        val savedUser = repository.findById(createUserDTO.id)
            ?: throw NoSuchElementException()
        eventPublisher.publishEvent(UserEvents.USER_CREATED, savedUser.toEventDTO())
        return savedUser
    }

    /**
     * Updates a user (no event is published, like in the original)
     *
     * @param input the input for the update
     * @return the updated user
     */
    suspend fun updateUser(input: UpdateUserInput): UserEntity {
        val user = repository.findById(input.id)
            ?: throw NoSuchElementException()
        input.firstName?.let { user.firstName = it }
        input.lastName?.let { user.lastName = it }
        input.birthday.ifPresent { user.birthday = it }
        input.gender.ifPresent { user.gender = it }
        return repository.save(user)
    }

}
