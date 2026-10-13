package org.misarch.discount.graphql.input

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import java.time.OffsetDateTime
import java.util.*

@GraphQLDescription("Input for the createDiscount mutation.")
class CreateDiscountInput(
    @property:GraphQLDescription("The discount applied to the order item.")
    val discount: Double,
    @property:GraphQLDescription("The maximum number of times a user can use this discount in bought ProductItems.")
    val maxUsagesPerUser: Int,
    @property:GraphQLDescription("The date and time until which the discount is valid.")
    val validUntil: OffsetDateTime,
    @property:GraphQLDescription("The date and time from which the discount is valid.")
    val validFrom: OffsetDateTime,
    @property:GraphQLDescription("The minimum order amount required to use this discount.")
    val minOrderAmount: Int?,
    @property:GraphQLDescription("The category ids to which the discount applies.")
    val discountAppliesToCategoryIds: List<UUID>,
    @property:GraphQLDescription("The product ids to which the discount applies.")
    val discountAppliesToProductIds: List<UUID>,
    @property:GraphQLDescription("The product variant ids to which the discount applies.")
    val discountAppliesToProductVariantIds: List<UUID>
)
