package org.misarch.discount.service

import com.expediagroup.graphql.generator.execution.OptionalInput
import org.misarch.common.event.EventPublisher
import org.misarch.discount.event.DiscountEvents
import org.misarch.discount.graphql.input.CreateCouponInput
import org.misarch.discount.graphql.input.RegisterCouponInput
import org.misarch.discount.graphql.input.UpdateCouponInput
import org.misarch.discount.persistence.model.CouponEntity
import org.misarch.discount.persistence.model.CouponRedemptionEntity
import org.misarch.discount.persistence.repository.CouponRedemptionRepository
import org.misarch.discount.persistence.repository.CouponRepository
import org.misarch.discount.persistence.repository.DiscountRepository
import org.springframework.stereotype.Service
import java.time.OffsetDateTime

/**
 * Service for [CouponEntity]s
 *
 * @param repository the provided repository
 * @param discountRepository the discount repository
 * @param couponRedemptionRepository the coupon redemption repository
 * @param eventPublisher the event publisher
 */
@Service
class CouponService(
    repository: CouponRepository,
    private val discountRepository: DiscountRepository,
    private val couponRedemptionRepository: CouponRedemptionRepository,
    private val eventPublisher: EventPublisher
) : BaseService<CouponEntity, CouponRepository>(repository) {

    /**
     * Creates a coupon
     *
     * @param couponInput the coupon to create
     * @return the created coupon
     */
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
    suspend fun updateCoupon(couponInput: UpdateCouponInput): CouponEntity {
        val coupon = repository.findById(couponInput.id) ?: throw NoSuchElementException()
        if (couponInput.code != null) {
            coupon.code = couponInput.code
        }
        if (couponInput.validUntil != null) {
            coupon.validUntil = couponInput.validUntil
        }
        if (couponInput.validFrom != null) {
            coupon.validFrom = couponInput.validFrom
        }
        if (couponInput.maxUsages is OptionalInput.Defined) {
            coupon.maxUsages = couponInput.maxUsages.value
        }
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
    suspend fun registerCoupon(input: RegisterCouponInput): CouponEntity {
        val coupon = repository.findByCode(input.code)
        require(coupon != null) { "Coupon with code ${input.code} does not exist" }
        val now = OffsetDateTime.now()
        require(coupon.validFrom.isBefore(now) && coupon.validUntil.isAfter(now)) {
            "Coupon with code ${input.code} is not valid currently"
        }
        couponRedemptionRepository.save(CouponRedemptionEntity(couponId = coupon.id!!, userId = input.userId, id = null))
        return repository.findById(coupon.id) ?: throw NoSuchElementException()
    }

}
