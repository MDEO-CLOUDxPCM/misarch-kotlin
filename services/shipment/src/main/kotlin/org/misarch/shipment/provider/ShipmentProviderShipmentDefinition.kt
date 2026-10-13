package org.misarch.shipment.provider

import java.util.UUID

/**
 * Entity for the shipment definition sent to the shipment provider on shipment creation
 *
 * @property shipmentId id of the shipment
 * @property ref reference of shipment method used for the shipment
 * @property quantity the number of items in the shipment
 * @property weight weight of the shipment in kg
 * @property address the address the shipment is sent to
 */
data class ShipmentProviderShipmentDefinition(
    val shipmentId: UUID,
    val ref: String,
    val quantity: Int,
    val weight: Double,
    val address: AddressDefinition
)

/**
 * Entity for the address definition sent to the shipment provider on shipment creation
 *
 * @property street1 first line of the address
 * @property street2 second line of the address
 * @property city city of the address
 * @property postalCode postal code of the address
 * @property country country of the address
 * @property companyName name of the company (sent as `null` if absent)
 */
data class AddressDefinition(
    val street1: String,
    val street2: String,
    val city: String,
    val postalCode: String,
    val country: String,
    val companyName: String?
)
