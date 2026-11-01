package org.misarch.invoice.service

import org.misarch.invoice.event.model.VendorAddressDTO
import org.misarch.invoice.persistence.model.VendorAddressEntity
import org.misarch.invoice.persistence.repository.VendorAddressRepository
import org.springframework.stereotype.Service

/**
 * Service for [VendorAddressEntity]s
 */
@Service
class VendorAddressService(
    private val repository: VendorAddressRepository
) {

    /**
     * Registers (or updates) a vendor address
     *
     * @param vendorAddressDTO the created vendor address
     */
    suspend fun registerVendorAddress(vendorAddressDTO: VendorAddressDTO) {
        repository.upsertVendorAddress(
            id = vendorAddressDTO.id,
            street1 = vendorAddressDTO.street1,
            street2 = vendorAddressDTO.street2,
            city = vendorAddressDTO.city,
            postalCode = vendorAddressDTO.postalCode,
            country = vendorAddressDTO.country,
            companyName = vendorAddressDTO.companyName
        )
    }

}
