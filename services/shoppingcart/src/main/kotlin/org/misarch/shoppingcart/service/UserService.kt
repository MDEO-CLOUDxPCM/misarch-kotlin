package org.misarch.shoppingcart.service

import org.misarch.shoppingcart.event.model.UserDTO
import org.misarch.shoppingcart.persistence.model.UserEntity
import org.misarch.shoppingcart.persistence.repository.UserRepository
import org.springframework.stereotype.Service
import java.time.OffsetDateTime

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
     * Registers a user with an empty shopping cart (replica, from `user/user/created`); idempotent
     *
     * @param userDTO the user to register
     */
    suspend fun registerUser(userDTO: UserDTO) {
        repository.createUser(userDTO.id, OffsetDateTime.now())
    }

}
