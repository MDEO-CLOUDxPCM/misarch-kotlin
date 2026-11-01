package org.misarch.discount.service

import org.misarch.common.event.EventPublisher
import org.misarch.common.graphql.ifPresent
import org.misarch.common.persistence.findByIdOrThrow
import org.misarch.discount.event.DiscountEvents
import org.misarch.discount.graphql.input.CreateCouponInput
import org.misarch.discount.graphql.input.RegisterCouponInput
import org.misarch.discount.graphql.input.UpdateCouponInput
import org.misarch.discount.persistence.model.CouponEntity
import org.misarch.discount.persistence.model.CouponRedemptionEntity
import org.misarch.discount.persistence.model.CouponRedemptionKey
import org.misarch.discount.persistence.repository.CouponRedemptionRepository
import org.misarch.discount.persistence.repository.CouponRepository
import org.misarch.discount.persistence.repository.DiscountRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * Service for [CouponEntity]s
 */
@Service
class CouponService(
    private val repository: CouponRepository,
    private val discountRepository: DiscountRepository,
    private val couponRedemptionRepository: CouponRedemptionRepository,
    private val eventPublisher: EventPublisher
) {

    /**
     * Creates a coupon
     *
     * @param couponInput the coupon to create
     * @return the created coupon
     */
    @Transactional
    suspend fun createCoupon(couponInput: CreateCouponInput): CouponEntity {
        require(
            discountRepository.existsById(couponInput.discountId)
        ) { "Discount with id ${couponInput.discountId} does not exist" }
        require(repository.findByCode(couponInput.code) == null) { "Coupon with code ${couponInput.code} already exists" }
        val coupon = CouponEntity(
            maxUsages = couponInput.maxUsages,
            usages = 0,
            validUntil = couponInput.validUntil,
            validFrom = couponInput.validFrom,
            code = couponInput.code,
            discountId = couponInput.discountId,
            id = null
        )
        val savedCoupon = repository.save(coupon)
        eventPublisher.publishEvent(DiscountEvents.COUPON_CREATED, savedCoupon.toEventDTO())
        return savedCoupon
    }

    /**
     * Updates a coupon
     *
     * @param couponInput the input for the update
     * @return the updated coupon
     */
    @Transactional
    suspend fun updateCoupon(couponInput: UpdateCouponInput): CouponEntity {
        val coupon = repository.findByIdOrThrow(couponInput.id)
        if (couponInput.code != null) {
            coupon.code = couponInput.code
        }
        if (couponInput.validUntil != null) {
            coupon.validUntil = couponInput.validUntil
        }
        if (couponInput.validFrom != null) {
            coupon.validFrom = couponInput.validFrom
        }
        couponInput.maxUsages.ifPresent { coupon.maxUsages = it }
        val updatedCoupon = repository.save(coupon)
        eventPublisher.publishEvent(DiscountEvents.COUPON_UPDATED, updatedCoupon.toEventDTO())
        return updatedCoupon
    }

    /**
     * Registers a coupon usage by a user
     *
     * @param input the input for the registration, defines the coupon and the user
     * @return the registered coupon
     */
    @Transactional
    suspend fun registerCoupon(input: RegisterCouponInput): CouponEntity {
        val coupon = repository.findByCode(input.code)
        require(coupon != null) { "Coupon with code ${input.code} does not exist" }
        val now = OffsetDateTime.now()
        require(coupon.validFrom.isBefore(now) && coupon.validUntil.isAfter(now)) {
            "Coupon with code ${input.code} is not valid currently"
        }
        couponRedemptionRepository.save(CouponRedemptionEntity(CouponRedemptionKey(coupon.id!!, input.userId)))
        return repository.findByIdOrThrow(coupon.id)
    }

}
