package org.misarch.`return`.event.model

import java.util.UUID

/**
 * Describes DTO of an OrderItem of an Order.
 *
 * @property id OrderItem UUID.
 * @property productVariantVersionId UUID of product variant version associated with OrderItem.
 * @property compensatableAmount Total cost of product item, which can also be refunded.
 */
data class OrderItemDTO(
    val id: UUID,
    val productVariantVersionId: UUID,
    val compensatableAmount: Long,
)
