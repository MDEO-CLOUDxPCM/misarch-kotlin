package org.misarch.address.persistence.model

import org.misarch.address.event.model.AddressDTO
import org.misarch.address.event.model.NameDTO
import org.misarch.address.event.model.UserAddressDTO
import org.misarch.address.event.model.VendorAddressDTO
import org.misarch.address.graphql.model.Address
import org.misarch.address.graphql.model.Name
import org.misarch.address.graphql.model.UserAddress
import org.misarch.address.graphql.model.VendorAddress
import org.misarch.common.persistence.BaseEntity
import org.misarch.common.persistence.requiredId
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Entity for addresses (both user and vendor); table `AddressEntity`.
 * A user address has a [userId], a vendor address has none. [version] is a BIGSERIAL set by the database
 * (the current vendor address is the one with the highest version).
 */
@Table
class AddressEntity(
    val firstName: String?,
    val lastName: String?,
    val street1: String,
    val street2: String,
    val city: String,
    val postalCode: String,
    val country: String,
    val companyName: String?,
    val userId: UUID?,
    val version: Long?,
    var archivedAt: OffsetDateTime?,
    @Id
    override val id: UUID?
) : BaseEntity<Address> {

    override fun toDTO(): Address {
        val name = if (firstName != null && lastName != null) {
            Name(
                firstName = firstName,
                lastName = lastName
            )
        } else {
            null
        }
        return if (userId != null) {
            UserAddress(
                id = requiredId,
                name = name,
                street1 = street1,
                street2 = street2,
                city = city,
                postalCode = postalCode,
                country = country,
                companyName = companyName,
                userId = userId,
                archivedAt = archivedAt
            )
        } else {
            VendorAddress(
                id = requiredId,
                name = name,
                street1 = street1,
                street2 = street2,
                city = city,
                postalCode = postalCode,
                country = country,
                companyName = companyName
            )
        }
    }

    /**
     * Converts the entity to an event DTO
     *
     * @return the event DTO
     */
    fun toEventDTO(): AddressDTO {
        val name = if (firstName != null && lastName != null) {
            NameDTO(
                firstName = firstName,
                lastName = lastName
            )
        } else {
            null
        }
        return if (userId != null) {
            UserAddressDTO(
                id = requiredId,
                name = name,
                street1 = street1,
                street2 = street2,
                city = city,
                postalCode = postalCode,
                country = country,
                companyName = companyName,
                userId = userId
            )
        } else {
            VendorAddressDTO(
                id = requiredId,
                name = name,
                street1 = street1,
                street2 = street2,
                city = city,
                postalCode = postalCode,
                country = country,
                companyName = companyName
            )
        }
    }

}
