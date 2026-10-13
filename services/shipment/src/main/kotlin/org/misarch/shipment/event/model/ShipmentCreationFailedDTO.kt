package org.misarch.shipment.event.model

import java.util.*

/**
 * DTO for a failed shipment creation
 * Caution: may contain invalid data, which may be the cause of the failure
 *
 * @property orderId unique identifier of the order, null if the shipment was created for a return
 * @property returnId unique identifier of the return, null if the shipment was created for an order
 * @property orderItemIds unique identifiers of the order items the shipment was created for
 * @property shipmentMethodId unique identifier of the shipment method
 * @property shipmentAddressId unique identifier of the address
 * @property reason the reason why the shipment creation failed
 */
data class ShipmentCreationFailedDTO(
    val orderId: UUID?,
    val returnId: UUID?,
    val orderItemIds: List<UUID>,
    val shipmentMethodId: UUID,
    val shipmentAddressId: UUID,
    val reason: String
)