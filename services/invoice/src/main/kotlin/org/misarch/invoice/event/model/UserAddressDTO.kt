package org.misarch.invoice.event.model

import java.util.UUID

/**
 * DTO of a created user address
 *
 * @property id unique identifier of the address
 * @property name the optional name of the addressee
 * @property street1 the first line of the street
 * @property street2 the second line of the street
 * @property city the city
 * @property postalCode the postal code
 * @property country the country
 * @property companyName the optional company name
 * @property userId the id of the user
 */
data class UserAddressDTO(
    val id: UUID,
    val name: NameDTO?,
    val street1: String,
    val street2: String,
    val city: String,
    val postalCode: String,
    val country: String,
    val companyName: String?,
    val userId: UUID
)

/**
 * DTO of the name of an addressee
 *
 * @property firstName the first name
 * @property lastName the last name
 */
data class NameDTO(
    val firstName: String?,
    val lastName: String?
)
