package org.misarch.simulation.model

/**
 * Body of the shipment status callback (`POST ${SHIPMENT_URL}/shipment/{id}/status`)
 *
 * @property status new status of the shipment
 */
data class ShipmentStatusUpdateDTO(
    val status: String,
)
