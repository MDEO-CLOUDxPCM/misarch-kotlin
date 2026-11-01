package org.misarch.discount.persistence.model

import org.springframework.data.annotation.Id
import org.springframework.data.domain.Persistable
import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

/**
 * Join table for the many-to-many relationship between coupons and the users who redeemed them; the primary key is the pair.
 *
 * @property key the coupon and the user
 */
@Table
class CouponRedemptionEntity(
    @Id
    val key: CouponRedemptionKey
) : Persistable<CouponRedemptionKey> {

    override fun getId(): CouponRedemptionKey = key

    /** Rows are only inserted and deleted, never updated: `save` always inserts. */
    override fun isNew(): Boolean = true
}

/**
 * Primary key of [CouponRedemptionEntity].
 *
 * @property couponId id of the coupon
 * @property userId id of the user
 */
data class CouponRedemptionKey(val couponId: UUID, val userId: UUID)
