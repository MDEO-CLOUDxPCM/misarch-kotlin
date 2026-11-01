package org.misarch.discount.event.model

import java.time.OffsetDateTime
import java.util.UUID

/**
 * Coupon DTO used for events
 *
 * @property id id of the coupon
 * @property maxUsages the maximum number of times the coupon can be used
 * @property validUntil the date and time until which the coupon is valid
 * @property validFrom the date and time from which the coupon is valid
 * @property code the code of the coupon
 * @property discountId the id of the discount the coupon is for
 */
data class CouponDTO(
    val id: UUID,
    val maxUsages: Int?,
    val validUntil: OffsetDateTime,
    val validFrom: OffsetDateTime,
    val code: String,
    val discountId: UUID
)
