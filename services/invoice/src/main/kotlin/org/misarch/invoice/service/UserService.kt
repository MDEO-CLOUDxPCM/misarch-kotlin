package org.misarch.invoice.service

import org.misarch.invoice.event.model.UserDTO
import org.misarch.invoice.persistence.model.UserEntity
import org.misarch.invoice.persistence.repository.UserRepository
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
     * Registers a user
     *
     * @param userDTO the created user
     */
    suspend fun registerUser(userDTO: UserDTO) {
        repository.createUser(userDTO.id, userDTO.firstName, userDTO.lastName)
    }

}
