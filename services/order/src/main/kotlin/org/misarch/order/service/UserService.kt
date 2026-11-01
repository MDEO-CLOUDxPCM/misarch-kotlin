package org.misarch.order.service

import org.misarch.order.event.model.UserAddressDTO
import org.misarch.order.event.model.UserDTO
import org.misarch.order.persistence.model.UserAddressKey
import org.misarch.order.persistence.model.UserEntity
import org.misarch.order.persistence.repository.UserAddressRepository
import org.misarch.order.persistence.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Service for [UserEntity]s and their addresses
 *
 * @param repository the repository for [UserEntity]s
 * @param userAddressRepository the repository for user addresses
 */
@Service
class UserService(
    private val repository: UserRepository,
    private val userAddressRepository: UserAddressRepository
) {

    /**
     * Registers a user
     *
     * @param userDTO the created user
     */
    suspend fun registerUser(userDTO: UserDTO) {
        repository.createUser(userDTO.id)
    }

    /**
     * Registers an address of a user; the user is registered too (the address event may arrive before the user event)
     *
     * @param userAddressDTO the created user address
     */
    @Transactional
    suspend fun registerUserAddress(userAddressDTO: UserAddressDTO) {
        repository.createUser(userAddressDTO.userId)
        userAddressRepository.createUserAddress(userAddressDTO.userId, userAddressDTO.id)
    }

    /**
     * Removes an archived address of a user (it can no longer be used for orders)
     *
     * @param userAddressDTO the archived user address
     */
    suspend fun archiveUserAddress(userAddressDTO: UserAddressDTO) {
        userAddressRepository.deleteById(UserAddressKey(userAddressDTO.userId, userAddressDTO.id))
    }

}
