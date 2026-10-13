package org.misarch.discount.persistence.repository

import org.misarch.discount.persistence.model.CouponEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import java.util.*

/**
 * Repository for [CouponEntity]s
 */
@Repository
interface CouponRepository : CoroutineCrudRepository<CouponEntity, UUID> {

    /**
     * Finds a coupon by its code
     *
     * @param code the code of the coupon
     */
    suspend fun findByCode(code: String): CouponEntity?

}
