package org.misarch.shipment.graphql.input

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import java.util.*

@GraphQLDescription("Input for a product variant version with a quantity.")
open class ProductVariantVersionWithQuantityInput(
    @property:GraphQLDescription("The product variant version id.")
    val productVariantVersionId: UUID,
    @property:GraphQLDescription("The quantity of the product variant version.")
    val quantity: Int
)