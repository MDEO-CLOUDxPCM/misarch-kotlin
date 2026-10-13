package org.misarch.address.event.model

import java.util.UUID

/**
 * Entity for the vendor address created event
 */
class VendorAddressDTO(
    id: UUID,
    name: NameDTO?,
    street1: String,
    street2: String,
    city: String,
    postalCode: String,
    country: String,
    companyName: String?
) : AddressDTO(id, name, street1, street2, city, postalCode, country, companyName)
