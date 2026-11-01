package org.misarch.simulation.connector

/**
 * Body of the shipment callback
 *
 * @property status new status of the shipment
 */
data class ShipmentStatusUpdateDTO(
    val status: String,
)
