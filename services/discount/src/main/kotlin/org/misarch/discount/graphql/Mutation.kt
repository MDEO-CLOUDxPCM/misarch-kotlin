package org.misarch.discount.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.server.operations.Mutation
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.graphql.authorizedUser
import org.misarch.discount.graphql.input.CreateCouponInput
import org.misarch.discount.graphql.input.CreateDiscountInput
import org.misarch.discount.graphql.input.RegisterCouponInput
import org.misarch.discount.graphql.input.UpdateCouponInput
import org.misarch.discount.graphql.input.UpdateDiscountInput
import org.misarch.discount.graphql.model.Coupon
import org.misarch.discount.graphql.model.Discount
import org.misarch.discount.service.CouponService
import org.misarch.discount.service.DiscountService
import org.springframework.stereotype.Component

/**
 * Defines GraphQL mutations
 */
@Component
class Mutation(
    private val discountService: DiscountService,
    private val couponService: CouponService
) : Mutation {

    @GraphQLDescription("Create a new discount")
    suspend fun createDiscount(
        @GraphQLDescription("Input for the createDiscount mutation")
        input: CreateDiscountInput,
        dfe: DataFetchingEnvironment
    ): Discount {
        dfe.authorizedUser.checkIsEmployee()
        val discount = discountService.createDiscount(input)
        return discount.toDTO()
    }

    @GraphQLDescription("Create a new coupon")
    suspend fun createCoupon(
        @GraphQLDescription("Input for the createCoupon mutation")
        input: CreateCouponInput,
        dfe: DataFetchingEnvironment
    ): Coupon {
        dfe.authorizedUser.checkIsEmployee()
        val coupon = couponService.createCoupon(input)
        return coupon.toDTO()
    }

    @GraphQLDescription("Register a coupon")
    suspend fun registerCoupon(
        @GraphQLDescription("Input for the registerCoupon mutation")
        input: RegisterCouponInput,
        dfe: DataFetchingEnvironment
    ): Coupon {
        if (dfe.authorizedUser.id != input.userId) {
            dfe.authorizedUser.checkIsEmployee()
        }
        val coupon = couponService.registerCoupon(input)
        return coupon.toDTO()
    }

    @GraphQLDescription("Update a discount")
    suspend fun updateDiscount(
        @GraphQLDescription("Input for the updateDiscount mutation")
        input: UpdateDiscountInput,
        dfe: DataFetchingEnvironment
    ): Discount {
        dfe.authorizedUser.checkIsEmployee()
        val discount = discountService.updateDiscount(input)
        return discount.toDTO()
    }

    @GraphQLDescription("Update a coupon")
    suspend fun updateCoupon(
        @GraphQLDescription("Input for the updateCoupon mutation")
        input: UpdateCouponInput,
        dfe: DataFetchingEnvironment
    ): Coupon {
        dfe.authorizedUser.checkIsEmployee()
        val coupon = couponService.updateCoupon(input)
        return coupon.toDTO()
    }
}
