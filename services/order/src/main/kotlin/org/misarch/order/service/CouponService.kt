package org.misarch.order.service

import org.misarch.order.event.model.CouponDTO
import org.misarch.order.persistence.model.CouponEntity
import org.misarch.order.persistence.repository.CouponRepository
import org.springframework.stereotype.Service

/**
 * Service for [CouponEntity]s
 *
 * @param repository the repository for [CouponEntity]s
 */
@Service
class CouponService(
    private val repository: CouponRepository
) {

    /**
     * Registers a coupon
     *
     * @param couponDTO the created coupon
     */
    suspend fun registerCoupon(couponDTO: CouponDTO) {
        repository.createCoupon(couponDTO.id)
    }

}
