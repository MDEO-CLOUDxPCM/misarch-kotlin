package org.misarch.shipment.event.model

import java.util.*

/**
 * Return DTO used for events
 *
 * @property id id of the return
 * @property orderId id of the order of which the items are being returned
 * @property orderItemIds ids of the order items returned
 */
data class ReturnDTO(
    val id: UUID,
    val orderId: UUID,
    val orderItemIds: List<UUID>
)