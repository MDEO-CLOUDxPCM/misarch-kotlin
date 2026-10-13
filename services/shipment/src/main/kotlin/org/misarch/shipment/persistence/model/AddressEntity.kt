package org.misarch.shipment.persistence.model

import org.misarch.common.persistence.BaseEntity
import org.misarch.shipment.graphql.model.Address
import org.misarch.shipment.graphql.model.UserAddress
import org.misarch.shipment.graphql.model.VendorAddress
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

/**
 * Entity for addresses (replica of the address service's user and vendor addresses)
 *
 * @property street1 first line of the address
 * @property street2 second line of the address
 * @property city city of the address
 * @property postalCode postal code of the address
 * @property country country of the address
 * @property companyName name of the company, null if the address is a user address
 * @property userId unique identifier of the user, null if the address is a vendor address
 * @property version BIGSERIAL set by the database (the current vendor address has the highest version)
 * @property id unique identifier of the address
 */
@Table
class AddressEntity(
    val street1: String,
    val street2: String,
    val city: String,
    val postalCode: String,
    val country: String,
    val companyName: String?,
    val userId: UUID?,
    val version: Long?,
    @Id
    override val id: UUID
) : BaseEntity<Address> {

    override fun toDTO(): Address {
        if (userId == null) {
            return VendorAddress(id)
        } else {
            return UserAddress(id)
        }
    }
}
