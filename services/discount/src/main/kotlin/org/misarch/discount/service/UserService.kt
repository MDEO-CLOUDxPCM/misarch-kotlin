package org.misarch.discount.service

import org.misarch.discount.event.model.UserDTO
import org.misarch.discount.persistence.model.UserEntity
import org.misarch.discount.persistence.repository.UserRepository
import org.springframework.stereotype.Service

/**
 * Service for [UserEntity]s
 *
 * @param repository the repository for [UserEntity]s
 */
@Service
class UserService(
    repository: UserRepository
) : BaseService<UserEntity, UserRepository>(repository) {

    /**
     * Registers a user
     *
     * @param userDTO the user to register
     */
    suspend fun registerUser(userDTO: UserDTO) {
        repository.createUser(userDTO.id)
    }

}
