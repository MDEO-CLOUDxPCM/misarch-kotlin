package org.misarch.discount.persistence.repository

import org.misarch.discount.persistence.model.CouponRedemptionEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import java.util.*

/**
 * Repository for [CouponRedemptionEntity]s
 */
@Repository
interface CouponRedemptionRepository : CoroutineCrudRepository<CouponRedemptionEntity, UUID>
