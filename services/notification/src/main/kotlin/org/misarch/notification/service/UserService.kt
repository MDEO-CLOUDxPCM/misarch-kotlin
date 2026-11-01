package org.misarch.notification.service

import org.misarch.notification.event.model.UserDTO
import org.misarch.notification.persistence.model.UserEntity
import org.misarch.notification.persistence.repository.UserRepository
import org.springframework.stereotype.Service

/**
 * Service for [UserEntity]s
 */
@Service
class UserService(
    private val repository: UserRepository
) {

    /**
     * Registers a user (replica, from `user/user/created`). Idempotent, so a redelivered event is acknowledged.
     *
     * @param userDTO the user to register
     */
    suspend fun registerUser(userDTO: UserDTO) {
        repository.createUser(userDTO.id)
    }

}
