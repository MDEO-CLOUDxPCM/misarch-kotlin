package org.misarch.discount.graphql.input

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.execution.OptionalInput
import java.time.OffsetDateTime
import java.util.UUID

@GraphQLDescription("Input for the updateDiscount mutation.")
class UpdateDiscountInput(
    @property:GraphQLDescription("The id of the discount to update.")
    val id: UUID,
    @property:GraphQLDescription("The discount applied to the order item.")
    val maxUsagesPerUser: OptionalInput<Int>,
    @property:GraphQLDescription("The date and time until which the discount is valid.")
    val validUntil: OffsetDateTime?,
    @property:GraphQLDescription("The date and time from which the discount is valid.")
    val validFrom: OffsetDateTime?,
    @property:GraphQLDescription("The minimum order amount required to use this discount.")
    val minOrderAmount: OptionalInput<Int>,
    @property:GraphQLDescription("Added category ids to which the discount applies.")
    val addedDiscountAppliesToCategoryIds: List<UUID>?,
    @property:GraphQLDescription("Removed category ids to which the discount applies.")
    val removedDiscountAppliesToCategoryIds: List<UUID>?,
    @property:GraphQLDescription("Added product ids to which the discount applies.")
    val addedDiscountAppliesToProductIds: List<UUID>?,
    @property:GraphQLDescription("Removed product ids to which the discount applies.")
    val removedDiscountAppliesToProductIds: List<UUID>?,
    @property:GraphQLDescription("Added product variant ids to which the discount applies.")
    val addedDiscountAppliesToProductVariantIds: List<UUID>?,
    @property:GraphQLDescription("Removed product variant ids to which the discount applies.")
    val removedDiscountAppliesToProductVariantIds: List<UUID>?
)
