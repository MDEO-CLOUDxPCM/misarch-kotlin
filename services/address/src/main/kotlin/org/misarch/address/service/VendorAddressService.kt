package org.misarch.address.service

import org.misarch.address.event.AddressEvents
import org.misarch.address.graphql.input.CreateVendorAddressInput
import org.misarch.address.persistence.model.AddressEntity
import org.misarch.address.persistence.repository.AddressRepository
import org.misarch.common.event.EventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Service for vendor addresses ([AddressEntity]s without user)
 *
 * @param repository the address repository
 * @param eventPublisher the event publisher
 */
@Service
class VendorAddressService(
    private val repository: AddressRepository,
    private val eventPublisher: EventPublisher
) {

    /**
     * Creates a vendor address and publishes `address/vendor-address/created`
     *
     * @param vendorAddressInput the vendor address to create
     * @return the created vendor address
     */
    @Transactional
    suspend fun createVendorAddress(vendorAddressInput: CreateVendorAddressInput): AddressEntity {
        val vendorAddress = AddressEntity(
            firstName = vendorAddressInput.name?.firstName,
            lastName = vendorAddressInput.name?.lastName,
            street1 = vendorAddressInput.street1,
            street2 = vendorAddressInput.street2,
            city = vendorAddressInput.city,
            postalCode = vendorAddressInput.postalCode,
            country = vendorAddressInput.country,
            companyName = vendorAddressInput.companyName,
            userId = null,
            id = null,
            version = null,
            archivedAt = null
        )
        val savedVendorAddress = repository.save(vendorAddress)
        eventPublisher.publishEvent(AddressEvents.VENDOR_ADDRESS_CREATED, savedVendorAddress.toEventDTO())
        return savedVendorAddress
    }

}
