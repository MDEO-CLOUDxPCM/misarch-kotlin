package org.misarch.shipment.event.model.order

import java.util.UUID

/**
 * Describes DTO of an OrderItem of an Order.
 *
 * @property id OrderItem UUID.
 * @property createdAt Timestamp when OrderItem was created.
 * @property productVariantVersionId UUID of product variant version associated with OrderItem.
 * @property productVariantId UUID of product variant associated with OrderItem.
 * @property taxRateVersionId UUID of tax rate version associated with OrderItem.
 * @property shoppingCartItemId UUID of shopping cart item associated with OrderItem.
 * @property count Specifies the quantity of the OrderItem.
 * @property compensatableAmount Total cost of product item, which can also be refunded.
 * @property shipmentMethodId UUID of shipment method of order item.
 * @property discountIds List of UUIDs of discounts applied to the order item.
 */
data class OrderItemDTO(
    val id: UUID,
    val createdAt: String,
    val productVariantVersionId: UUID,
    val productVariantId: UUID,
    val taxRateVersionId: UUID,
    val shoppingCartItemId: UUID,
    val count: Long,
    val compensatableAmount: Long,
    val shipmentMethodId: UUID,
    val discountIds: List<UUID>
)