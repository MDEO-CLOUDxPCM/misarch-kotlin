package org.misarch.discount.event.model

import java.time.OffsetDateTime
import java.util.UUID

/**
 * Discount DTO used for events
 *
 * @property id id of the discount
 * @property discount the discount applied to the order item
 * @property maxUsagesPerUser the maximum number of times a user can use this discount in bought ProductItems
 * @property validUntil the date and time until which the discount is valid
 * @property validFrom the date and time from which the discount is valid
 * @property minOrderAmount the minimum order amount required to use this discount
 * @property discountAppliesToCategoryIds the category ids to which the discount applies
 * @property discountAppliesToProductIds the product ids to which the discount applies
 * @property discountAppliesToProductVariantIds the product variant ids to which the discount applies
 */
data class DiscountDTO(
    val id: UUID,
    val discount: Double,
    val maxUsagesPerUser: Int?,
    val validUntil: OffsetDateTime,
    val validFrom: OffsetDateTime,
    val minOrderAmount: Int?,
    val discountAppliesToCategoryIds: List<UUID>,
    val discountAppliesToProductIds: List<UUID>,
    val discountAppliesToProductVariantIds: List<UUID>
)
