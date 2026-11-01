package org.misarch.order.persistence.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

/**
 * Entity for coupons (replica, fed by `discount/coupon/created`)
 *
 * @property id unique identifier of the coupon
 */
@Table
class CouponEntity(
    @Id
    val id: UUID
)
