package org.misarch.discount.persistence.repository

import org.misarch.discount.persistence.model.CouponRedemptionEntity
import org.misarch.discount.persistence.model.CouponRedemptionKey
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * Repository for [CouponRedemptionEntity]s
 */
interface CouponRedemptionRepository : CoroutineCrudRepository<CouponRedemptionEntity, CouponRedemptionKey>
