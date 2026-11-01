package org.misarch.shipment.service

import org.misarch.shipment.event.model.AddressDTO
import org.misarch.shipment.event.model.UserAddressDTO
import org.misarch.shipment.event.model.VendorAddressDTO
import org.misarch.shipment.persistence.model.AddressEntity
import org.misarch.shipment.persistence.repository.AddressRepository
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Service for [AddressEntity]s
 */
@Service
class AddressService(
    private val repository: AddressRepository
) {

    /**
     * Registers a user address
     *
     * @param userAddressDTO the user to register
     */
    suspend fun registerUserAddress(userAddressDTO: UserAddressDTO) {
        createAddress(userAddressDTO, userAddressDTO.userId)
    }

    /**
     * Registers a vendor address
     *
     * @param vendorAddressDTO the vendor to register
     */
    suspend fun registerVendorAddress(vendorAddressDTO: VendorAddressDTO) {
        createAddress(vendorAddressDTO, null)
    }

    /**
     * Creates an address
     *
     * @param addressDTO the address to create
     * @param userId the id of the user, if any
     */
    private suspend fun createAddress(addressDTO: AddressDTO, userId: UUID?) {
        repository.createAddress(
            addressDTO.id,
            userId,
            addressDTO.street1,
            addressDTO.street2,
            addressDTO.city,
            addressDTO.postalCode,
            addressDTO.country,
            addressDTO.companyName
        )
    }

}
