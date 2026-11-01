package org.misarch.inventory.event.model

import org.misarch.common.event.MillisecondTimestampSerializer
import tools.jackson.databind.annotation.JsonSerialize
import java.time.OffsetDateTime
import java.util.UUID

/**
 * DTO of an item of a placed order.
 *
 * @property id unique identifier of the order item
 * @property createdAt the date and time the item was created
 * @property productVariantId the id of the product variant
 * @property productVariantVersionId the id of the product variant version
 * @property taxRateVersionId the id of the tax rate version
 * @property shoppingCartItemId the id of the shopping cart item
 * @property count the quantity
 * @property compensatableAmount what the buyer pays for the item
 * @property shipmentMethodId the id of the shipment method
 * @property discountIds the ids of the discounts applied to the item
 */
data class OrderItemDTO(
    val id: UUID,
    @get:JsonSerialize(using = MillisecondTimestampSerializer::class)
    val createdAt: OffsetDateTime,
    val productVariantId: UUID,
    val productVariantVersionId: UUID,
    val taxRateVersionId: UUID,
    val shoppingCartItemId: UUID,
    val count: Long,
    val compensatableAmount: Long,
    val shipmentMethodId: UUID,
    val discountIds: List<UUID>
)
