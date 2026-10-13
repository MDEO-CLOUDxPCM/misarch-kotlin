package org.misarch.address.event.model

import java.util.UUID

/**
 * Address DTO used for events
 * Parent of [UserAddressDTO] and [VendorAddressDTO]
 *
 * @property id id of the address
 * @property name name of the address
 * @property street1 first line of the address
 * @property street2 second line of the address
 * @property city city of the address
 * @property postalCode postal code of the address
 * @property country country of the address
 * @property companyName company name of the address
 */
abstract class AddressDTO(
    val id: UUID,
    val name: NameDTO?,
    val street1: String,
    val street2: String,
    val city: String,
    val postalCode: String,
    val country: String,
    val companyName: String?,
)
