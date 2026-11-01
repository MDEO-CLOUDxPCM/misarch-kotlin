package org.misarch.discount.persistence.repository

import org.misarch.discount.persistence.model.CouponEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.UUID

/**
 * Repository for [CouponEntity]s
 */
interface CouponRepository : CoroutineCrudRepository<CouponEntity, UUID> {

    /**
     * Finds a coupon by its code
     *
     * @param code the code of the coupon
     */
    suspend fun findByCode(code: String): CouponEntity?

}
