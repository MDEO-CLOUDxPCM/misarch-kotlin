package org.misarch.shipment.event.model

import java.util.*

/**
 * DTO for archive shipment method events
 *
 * @property id id of the archived shipment method
 */
data class ArchiveShipmentMethodDTO(
    val id: UUID
)