package org.misarch.discount.persistence.model

import org.misarch.common.persistence.BaseEntity
import org.misarch.common.persistence.requiredId
import org.misarch.discount.event.model.CouponDTO
import org.misarch.discount.graphql.model.Coupon
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.ReadOnlyProperty
import org.springframework.data.relational.core.mapping.Table
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Entity for coupons
 *
 * @property usages the number of times the coupon has been used (maintained by the database trigger
 *   `update_coupon_usages`, never written by the service)
 * @property maxUsages the maximum number of times the coupon can be used
 * @property validUntil the date and time until which the coupon is valid
 * @property validFrom the date and time from which the coupon is valid
 * @property code the code of the coupon
 * @property discountId the id of the discount the coupon is for
 * @property id unique identifier of the coupon
 */
@Table
class CouponEntity(
    @ReadOnlyProperty
    val usages: Int,
    var maxUsages: Int?,
    var validUntil: OffsetDateTime,
    var validFrom: OffsetDateTime,
    var code: String,
    val discountId: UUID,
    @Id
    override val id: UUID?
) : BaseEntity<Coupon> {

    override fun toDTO(): Coupon {
        return Coupon(
            id = requiredId,
            usages = usages,
            maxUsages = maxUsages,
            validUntil = validUntil,
            validFrom = validFrom,
            code = code,
            discountId = discountId
        )
    }

    /**
     * Converts the entity to an event DTO
     *
     * @return the event DTO
     */
    fun toEventDTO(): CouponDTO {
        return CouponDTO(
            id = requiredId,
            maxUsages = maxUsages,
            validUntil = validUntil,
            validFrom = validFrom,
            code = code,
            discountId = discountId
        )
    }

}
