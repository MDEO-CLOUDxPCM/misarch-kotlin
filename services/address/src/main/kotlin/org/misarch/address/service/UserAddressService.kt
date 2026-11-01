package org.misarch.address.service

import org.misarch.address.event.AddressEvents
import org.misarch.address.event.model.ArchiveUserAddressDTO
import org.misarch.address.graphql.input.ArchiveUserAddressInput
import org.misarch.address.graphql.input.CreateUserAddressInput
import org.misarch.address.persistence.model.AddressEntity
import org.misarch.address.persistence.repository.AddressRepository
import org.misarch.address.persistence.repository.UserRepository
import org.misarch.common.event.EventPublisher
import org.misarch.common.graphql.AuthorizedUser
import org.misarch.common.persistence.findByIdOrThrow
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * Service for user addresses ([AddressEntity]s with a user)
 *
 * @param repository the address repository
 * @param userRepository the user repository
 * @param eventPublisher the event publisher
 */
@Service
class UserAddressService(
    private val repository: AddressRepository,
    private val userRepository: UserRepository,
    private val eventPublisher: EventPublisher
) {

    /**
     * Creates a user address and publishes `address/user-address/created`
     *
     * @param userAddressInput the user address to create
     * @return the created user address
     */
    @Transactional
    suspend fun createUserAddress(userAddressInput: CreateUserAddressInput): AddressEntity {
        require(userRepository.existsById(userAddressInput.userId)) {
            "User with id ${userAddressInput.userId} does not exist"
        }
        val userAddress = AddressEntity(
            firstName = userAddressInput.name?.firstName,
            lastName = userAddressInput.name?.lastName,
            street1 = userAddressInput.street1,
            street2 = userAddressInput.street2,
            city = userAddressInput.city,
            postalCode = userAddressInput.postalCode,
            country = userAddressInput.country,
            companyName = userAddressInput.companyName,
            userId = userAddressInput.userId,
            id = null,
            version = null,
            archivedAt = null
        )
        val savedUserAddress = repository.save(userAddress)
        eventPublisher.publishEvent(AddressEvents.USER_ADDRESS_CREATED, savedUserAddress.toEventDTO())
        return savedUserAddress
    }

    /**
     * Archives a user address (also checks permissions) and publishes `address/user-address/archived`
     *
     * @param archiveUserAddressInput defines the user address to archive
     * @param authorizedUser the user executing the mutation
     * @return the archived user address
     */
    @Transactional
    suspend fun archiveUserAddress(
        archiveUserAddressInput: ArchiveUserAddressInput, authorizedUser: AuthorizedUser
    ): AddressEntity {
        val userAddress = repository.findByIdOrThrow(archiveUserAddressInput.id)
        if (userAddress.userId != authorizedUser.id) {
            authorizedUser.checkIsEmployee()
        }
        userAddress.archivedAt = OffsetDateTime.now()
        val savedUserAddress = repository.save(userAddress)
        eventPublisher.publishEvent(
            AddressEvents.USER_ADDRESS_ARCHIVED, ArchiveUserAddressDTO(userAddress.id!!, userAddress.userId!!)
        )
        return savedUserAddress
    }

}
