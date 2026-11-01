package org.misarch.order.event.model

import java.util.UUID

/**
 * DTO for a created coupon (the replica uses only the id)
 *
 * @property id unique identifier of the coupon
 * @property discountId the id of the coupon's discount (only read by the local copies, docs/MECHANISMS.md)
 */
data class CouponDTO(
    val id: UUID,
    val discountId: UUID? = null
)
