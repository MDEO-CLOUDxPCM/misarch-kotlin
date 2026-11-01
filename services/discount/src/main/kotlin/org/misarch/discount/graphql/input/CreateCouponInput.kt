package org.misarch.discount.graphql.input

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import java.time.OffsetDateTime
import java.util.UUID

@GraphQLDescription("Input for the createCoupon mutation.")
class CreateCouponInput(
    @property:GraphQLDescription("The maximum number of times the coupon can be used.")
    val maxUsages: Int,
    @property:GraphQLDescription("The date and time until which the coupon is valid.")
    val validUntil: OffsetDateTime,
    @property:GraphQLDescription("The date and time from which the coupon is valid.")
    val validFrom: OffsetDateTime,
    @property:GraphQLDescription("The code of the coupon.")
    val code: String,
    @property:GraphQLDescription("The id of the discount the coupon is for.")
    val discountId: UUID
)
