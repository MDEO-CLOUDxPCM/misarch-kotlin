package org.misarch.invoice.event.model

import java.util.UUID

/**
 * DTO of a created vendor address
 *
 * @property id unique identifier of the address
 * @property street1 the first line of the street
 * @property street2 the second line of the street
 * @property city the city
 * @property postalCode the postal code
 * @property country the country
 * @property companyName the optional company name
 */
data class VendorAddressDTO(
    val id: UUID,
    val street1: String,
    val street2: String,
    val city: String,
    val postalCode: String,
    val country: String,
    val companyName: String?
)
