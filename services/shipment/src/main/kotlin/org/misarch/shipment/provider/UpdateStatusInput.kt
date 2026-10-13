package org.misarch.shipment.provider

import org.misarch.shipment.graphql.model.ShipmentStatus

/**
 * Input for updating the status of a shipment
 *
 * @property status new status of the shipment
 */
data class UpdateStatusInput(
    val status: ShipmentStatus
)
