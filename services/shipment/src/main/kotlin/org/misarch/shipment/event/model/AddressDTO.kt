package org.misarch.shipment.event.model

import java.util.UUID

/**
 * Fields present on both [UserAddressDTO] and [VendorAddressDTO]
 */
interface AddressDTO {
    /**
     * The id of the address
     */
    val id: UUID

    /**
     * The first line of the street of the address
     */
    val street1: String

    /**
     * The second line of the street of the address
     */
    val street2: String

    /**
     * The city of the address
     */
    val city: String

    /**
     * The postal code of the address
     */
    val postalCode: String

    /**
     * The country of the address
     */
    val country: String

    /**
     * The company name of the address, if any
     */
    val companyName: String?
}