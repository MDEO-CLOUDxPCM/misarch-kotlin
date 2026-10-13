package org.misarch.shipment.event.model

import java.util.*

/**
 * Address DTO used for events
 *
 * @property id id of the address
 * @property street1 first line of the address
 * @property street2 second line of the address
 * @property city city of the address
 * @property postalCode postal code of the address
 * @property country country of the address
 * @property companyName name of the company
 */
data class VendorAddressDTO(
    override val id: UUID,
    override val street1: String,
    override val street2: String,
    override val city: String,
    override val postalCode: String,
    override val country: String,
    override val companyName: String?
) : AddressDTO