package org.misarch.inventory.event.model

import tools.jackson.databind.JsonNode
import java.util.UUID

/**
 * Not all product items of an order could be reserved (the reserved ones have been released again)
 *
 * @property order the order as received in `order/order/created`
 * @property failedProductVariantIds the product variants without enough product items in storage
 */
data class ReservationFailedDTO(
    val order: JsonNode,
    val failedProductVariantIds: List<UUID>,
)
