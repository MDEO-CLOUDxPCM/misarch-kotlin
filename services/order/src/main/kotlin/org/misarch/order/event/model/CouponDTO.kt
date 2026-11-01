package org.misarch.order.event.model

import java.util.UUID

/**
 * DTO for a created coupon (only the id is used)
 *
 * @property id unique identifier of the coupon
 */
data class CouponDTO(
    val id: UUID
)
