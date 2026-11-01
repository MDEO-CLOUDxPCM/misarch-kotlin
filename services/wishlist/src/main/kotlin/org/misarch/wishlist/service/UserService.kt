package org.misarch.wishlist.service

import org.misarch.wishlist.event.model.UserDTO
import org.misarch.wishlist.persistence.model.UserEntity
import org.misarch.wishlist.persistence.repository.UserRepository
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
     * Registers a user (replica, from `user/user/created`); idempotent
     *
     * @param userDTO the user to register
     */
    suspend fun registerUser(userDTO: UserDTO) {
        repository.createUser(userDTO.id)
    }

}
