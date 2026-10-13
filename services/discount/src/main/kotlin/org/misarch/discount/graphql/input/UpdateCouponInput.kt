package org.misarch.discount.graphql.input

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.execution.OptionalInput
import java.time.OffsetDateTime
import java.util.*

@GraphQLDescription("Input for the updateCoupon mutation.")
class UpdateCouponInput(
    @property:GraphQLDescription("The id of the coupon to update.")
    val id: UUID,
    @property:GraphQLDescription("The code of the coupon.")
    val code: String?,
    @property:GraphQLDescription("The date and time until which the coupon is valid.")
    val validUntil: OffsetDateTime?,
    @property:GraphQLDescription("The date and time from which the coupon is valid.")
    val validFrom: OffsetDateTime?,
    @property:GraphQLDescription("The maximum number of times the coupon can be used.")
    val maxUsages: OptionalInput<Int>
)
