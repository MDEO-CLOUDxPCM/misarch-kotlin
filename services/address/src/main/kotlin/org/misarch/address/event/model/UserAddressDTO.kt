package org.misarch.address.event.model

import java.util.UUID

/**
 * Entity for the user address created event
 *
 * @property userId id of the user the address belongs to
 */
class UserAddressDTO(
    id: UUID,
    name: NameDTO?,
    street1: String,
    street2: String,
    city: String,
    postalCode: String,
    country: String,
    companyName: String?,
    val userId: UUID,
) : AddressDTO(id, name, street1, street2, city, postalCode, country, companyName)
