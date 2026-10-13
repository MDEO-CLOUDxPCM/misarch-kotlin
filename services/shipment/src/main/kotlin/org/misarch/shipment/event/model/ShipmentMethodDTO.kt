package org.misarch.shipment.event.model

import java.util.*

/**
 * Entity for the shipment method created event
 *
 * @property id id of the shipment method
 * @property name name of the shipment method
 * @property description description of the shipment method
 * @property externalReference reference of the shipment method used by the external shipment provider
 * @property baseFees base fees for the shipment method
 * @property feesPerItem fees per item for the shipment method
 * @property feesPerKg fees per kg for the shipment method
 */
class ShipmentMethodDTO(
    val id: UUID,
    val name: String,
    val description: String,
    val externalReference: String,
    val baseFees: Int,
    val feesPerItem: Int,
    val feesPerKg: Int
)