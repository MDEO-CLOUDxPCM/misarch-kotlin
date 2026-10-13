package org.misarch.discount.graphql.input

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import java.util.UUID

@GraphQLDescription("Input for the registerCoupon mutation")
class RegisterCouponInput(
    @property:GraphQLDescription("The code of the coupon.")
    val code: String,
    @property:GraphQLDescription("The user who wants to register the coupon.")
    val userId: UUID
)
