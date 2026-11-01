package org.misarch.order.client.model

import java.util.UUID

/**
 * Input of discount's `findApplicableDiscounts`
 *
 * @property userId the id of the user ordering
 * @property productVariants the ordered product variants
 * @property orderAmount the amount of the order (sum of price times count)
 */
data class FindApplicableDiscountsInput(
    val userId: UUID,
    val productVariants: List<FindApplicableDiscountsProductVariantInput>,
    val orderAmount: Int
)

/**
 * An ordered product variant in [FindApplicableDiscountsInput]
 *
 * @property productVariantId the id of the product variant
 * @property count the quantity
 * @property couponIds the ids of the coupons used for it
 */
data class FindApplicableDiscountsProductVariantInput(
    val productVariantId: UUID,
    val count: Long,
    val couponIds: Set<UUID>
)
