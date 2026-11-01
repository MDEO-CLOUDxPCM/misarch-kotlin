package org.misarch.user.service

import org.misarch.common.event.EventPublisher
import org.misarch.common.graphql.ifPresent
import org.misarch.common.persistence.findByIdOrThrow
import org.misarch.user.event.UserEvents
import org.misarch.user.event.model.CreateUserDTO
import org.misarch.user.graphql.input.UpdateUserInput
import org.misarch.user.persistence.model.UserEntity
import org.misarch.user.persistence.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
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
     * FIXED (docs/fixes/user.md): one transaction for insert and publish (a failed publish rolls the insert back, so
     * the redelivery creates and publishes again; the original kept the row and every redelivery failed on the
     * duplicate key, so `user/user/created` was never published), and idempotent (a redelivery of an event that was
     * already processed is acknowledged without a second insert or publish).
     *
     * @param createUserDTO defines the new user
     * @return the created user
     */
    @Transactional
    suspend fun createUser(createUserDTO: CreateUserDTO): UserEntity {
        val inserted = repository.createUser(
            id = createUserDTO.id,
            username = createUserDTO.username,
            firstName = createUserDTO.firstName,
            lastName = createUserDTO.lastName,
            dateJoined = OffsetDateTime.now()
        )
        val savedUser = repository.findByIdOrThrow(createUserDTO.id)
        if (inserted > 0) {
            eventPublisher.publishEvent(UserEvents.USER_CREATED, savedUser.toEventDTO())
        }
        return savedUser
    }

    /**
     * Updates a user (no event is published, like in the original)
     *
     * @param input the input for the update
     * @return the updated user
     */
    @Transactional
    suspend fun updateUser(input: UpdateUserInput): UserEntity {
        val user = repository.findByIdOrThrow(input.id)
        input.firstName?.let { user.firstName = it }
        input.lastName?.let { user.lastName = it }
        input.birthday.ifPresent { user.birthday = it }
        input.gender.ifPresent { user.gender = it }
        return repository.save(user)
    }

}
