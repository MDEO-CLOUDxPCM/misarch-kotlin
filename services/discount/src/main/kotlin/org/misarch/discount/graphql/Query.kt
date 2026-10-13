package org.misarch.discount.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.InaccessibleDirective
import com.expediagroup.graphql.server.operations.Query
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.graphql.authorizedUser
import org.misarch.common.graphql.dataLoader
import org.misarch.discount.graphql.dataloader.CouponDataLoader
import org.misarch.discount.graphql.dataloader.DiscountDataLoader
import org.misarch.discount.graphql.input.FindApplicableDiscountsInput
import org.misarch.discount.graphql.model.Coupon
import org.misarch.discount.graphql.model.Discount
import org.misarch.discount.graphql.model.DiscountsForProductVariant
import org.misarch.discount.service.DiscountService
import org.springframework.stereotype.Component
import java.util.*
import java.util.concurrent.CompletableFuture

/**
 * Defines GraphQL queries
 *
 * @property discountService service for discounts
 */
@Component
class Query(
    private val discountService: DiscountService
) : Query {

    @GraphQLDescription("Get a discount by id")
    fun discount(
        @GraphQLDescription("The id of the discount")
        id: UUID,
        dfe: DataFetchingEnvironment
    ): CompletableFuture<Discount> {
        dfe.authorizedUser.checkIsEmployee()
        return dfe.dataLoader<UUID, Discount>(DiscountDataLoader::class.simpleName!!).load(id)
    }

    @GraphQLDescription("Get a coupon by id")
    fun coupon(
        @GraphQLDescription("The id of the coupon")
        id: UUID,
        dfe: DataFetchingEnvironment
    ): CompletableFuture<Coupon> {
        dfe.authorizedUser.checkIsEmployee()
        return dfe.dataLoader<UUID, Coupon>(CouponDataLoader::class.simpleName!!).load(id)
    }

    @GraphQLDescription("Find all applicable discounts for a user and a list of product variant, count and coupon id triples")
    @InaccessibleDirective
    suspend fun findApplicableDiscounts(
        @GraphQLDescription("The input for the findApplicableDiscounts query.")
        input: FindApplicableDiscountsInput
    ): List<DiscountsForProductVariant> {
        return discountService.findApplicableDiscounts(input)
    }

}
