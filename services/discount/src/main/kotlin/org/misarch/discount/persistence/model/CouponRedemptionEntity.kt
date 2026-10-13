package org.misarch.discount.persistence.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.util.*

/**
 * Join table for the many-to-many relationship between coupons and users
 *
 * @property couponId id of the coupon
 * @property userId id of the user
 * @property id unique identifier of the join table row, technical requirement, not used in the domain
 */
@Table
class CouponRedemptionEntity(
    val couponId: UUID,
    val userId: UUID,
    @Id
    val id: UUID?
)
