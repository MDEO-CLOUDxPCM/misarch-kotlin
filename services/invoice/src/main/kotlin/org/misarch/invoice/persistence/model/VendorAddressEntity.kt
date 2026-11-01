package org.misarch.invoice.persistence.model

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.ReadOnlyProperty
import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

/**
 * Entity for vendor addresses (replica, fed by `address/vendor-address/created`). Rows written by earlier versions
 * may hold only the id.
 *
 * @property street1 the first line of the street
 * @property street2 the second line of the street
 * @property city the city
 * @property postalCode the postal code
 * @property country the country
 * @property companyName the optional company name
 * @property insertionOrder the order of arrival (set by the database)
 * @property id unique identifier of the vendor address
 */
@Table
class VendorAddressEntity(
    val street1: String?,
    val street2: String?,
    val city: String?,
    val postalCode: String?,
    val country: String?,
    val companyName: String?,
    @ReadOnlyProperty
    val insertionOrder: Long?,
    @Id
    val id: UUID
)
