package org.misarch.order.persistence.repository

import org.misarch.order.persistence.model.CouponEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.UUID

/**
 * Repository for [CouponEntity]s
 */
interface CouponRepository : CoroutineCrudRepository<CouponEntity, UUID> {

    /**
     * Creates a coupon (replica); an existing one is kept
     *
     * @param id the id of the coupon
     */
    @Modifying
    @Query("INSERT INTO CouponEntity (id) VALUES (:id) ON CONFLICT (id) DO NOTHING")
    suspend fun createCoupon(id: UUID)

}
