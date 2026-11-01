package org.misarch.invoice.service

import org.misarch.invoice.event.model.ArchivedUserAddressDTO
import org.misarch.invoice.event.model.UserAddressDTO
import org.misarch.invoice.persistence.model.UserAddressEntity
import org.misarch.invoice.persistence.repository.UserAddressRepository
import org.springframework.stereotype.Service

/**
 * Service for [UserAddressEntity]s
 */
@Service
class UserAddressService(
    private val repository: UserAddressRepository
) {

    /**
     * Registers a user address (independent of the user, whose event may arrive later)
     *
     * @param userAddressDTO the created user address
     */
    suspend fun registerUserAddress(userAddressDTO: UserAddressDTO) {
        repository.createUserAddress(
            id = userAddressDTO.id,
            userId = userAddressDTO.userId,
            street1 = userAddressDTO.street1,
            street2 = userAddressDTO.street2,
            city = userAddressDTO.city,
            postalCode = userAddressDTO.postalCode,
            country = userAddressDTO.country,
            companyName = userAddressDTO.companyName,
            firstName = userAddressDTO.name?.firstName,
            lastName = userAddressDTO.name?.lastName
        )
    }

    /**
     * Marks a user address as archived
     *
     * @param archivedUserAddressDTO the archived user address
     */
    suspend fun archiveUserAddress(archivedUserAddressDTO: ArchivedUserAddressDTO) {
        repository.archiveUserAddress(archivedUserAddressDTO.id, archivedUserAddressDTO.userId)
    }

}
