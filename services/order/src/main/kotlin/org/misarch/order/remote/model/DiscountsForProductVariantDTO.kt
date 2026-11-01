package org.misarch.order.remote.model

import java.util.UUID

/**
 * The discounts applicable to a product variant, as returned by the discount service
 *
 * @property productVariantId the id of the product variant
 * @property discounts the applicable discounts
 */
data class DiscountsForProductVariantDTO(
    val productVariantId: UUID,
    val discounts: List<DiscountDTO>
)

/**
 * An applicable discount
 *
 * @property id unique identifier of the discount
 * @property discount the discount (fraction of the price)
 */
data class DiscountDTO(
    val id: UUID,
    val discount: Double
)
