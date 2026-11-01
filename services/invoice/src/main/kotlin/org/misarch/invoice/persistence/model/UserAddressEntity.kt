package org.misarch.invoice.persistence.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

/**
 * Entity for user addresses (replica, fed by `address/user-address/created` and `archived`). Archived addresses are
 * kept: orders placed with them still get their invoice.
 *
 * @property userId the id of the user
 * @property street1 the first line of the street
 * @property street2 the second line of the street
 * @property city the city
 * @property postalCode the postal code
 * @property country the country
 * @property companyName the optional company name
 * @property firstName the optional first name of the addressee
 * @property lastName the optional last name of the addressee
 * @property isArchived whether the address is archived
 * @property id unique identifier of the address
 */
@Table
class UserAddressEntity(
    val userId: UUID,
    val street1: String,
    val street2: String,
    val city: String,
    val postalCode: String,
    val country: String,
    val companyName: String?,
    val firstName: String?,
    val lastName: String?,
    val isArchived: Boolean,
    @Id
    val id: UUID
)
