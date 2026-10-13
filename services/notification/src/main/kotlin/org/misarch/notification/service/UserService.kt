package org.misarch.notification.service

import org.misarch.notification.event.model.UserDTO
import org.misarch.notification.persistence.model.UserEntity
import org.misarch.notification.persistence.repository.UserRepository
import org.springframework.stereotype.Service

/**
 * Service for [UserEntity]s
 *
 * @param repository the repository for [UserEntity]s
 */
@Service
class UserService(
    private val repository: UserRepository
) {

    /**
     * Registers a user (replica, from `user/user/created`). A duplicate id fails (primary key), so Dapr redelivers,
     * like in the original service.
     *
     * @param userDTO the user to register
     */
    suspend fun registerUser(userDTO: UserDTO) {
        repository.createUser(userDTO.id)
    }

}
