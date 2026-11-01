package org.misarch.discount.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import java.util.UUID

@GraphQLDescription("A list of discounts for a product variant (and count)")
class DiscountsForProductVariant(
    @property:GraphQLDescription("The product variant id for which discounts should be computed.")
    val productVariantId: UUID,
    @property:GraphQLDescription("The number of items to which the discounts should be applied.")
    val count: Int,
    @property:GraphQLDescription("The list of applicable discounts")
    val discounts: List<Discount>
)
