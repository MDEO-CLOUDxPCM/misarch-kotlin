package org.misarch.review.service

import org.misarch.review.event.model.UserDTO
import org.misarch.review.persistence.model.UserEntity
import org.misarch.review.persistence.repository.UserRepository
import org.springframework.stereotype.Service

/**
 * Service for [UserEntity]s
 */
@Service
class UserService(
    private val repository: UserRepository
) {

    /**
     * Registers a user (replica); idempotent
     *
     * @param userDTO the user to register
     */
    suspend fun registerUser(userDTO: UserDTO) {
        repository.createUser(userDTO.id)
    }

}
