package org.misarch.discount.graphql.input

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.InaccessibleDirective
import java.util.UUID

@GraphQLDescription("Input for the findApplicableDiscounts query.")
@InaccessibleDirective
class FindApplicableDiscountsInput(
    @property:GraphQLDescription("The user id for which discounts should be computed.")
    val userId: UUID,
    @property:GraphQLDescription("The list of product variants for which discounts should be computed.")
    val productVariants: List<FindApplicableDiscountsProductVariantInput>,
    @property:GraphQLDescription("The order amount, used to filter applicable discounts.")
    val orderAmount: Int
)

@GraphQLDescription("Triple of a product variant id, a count, and a list of coupon ids for which discounts should be computed")
@InaccessibleDirective
class FindApplicableDiscountsProductVariantInput(
    @property:GraphQLDescription("The product variant id for which discounts should be computed.")
    val productVariantId: UUID,
    @property:GraphQLDescription("The number of items to which the discounts should be applied.")
    val count: Int,
    @property:GraphQLDescription("The list of coupon ids for which discounts should be computed.")
    val couponIds: List<UUID>
)
