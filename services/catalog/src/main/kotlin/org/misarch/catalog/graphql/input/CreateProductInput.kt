package org.misarch.catalog.graphql.input

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import java.util.UUID

@GraphQLDescription("Input for the createProduct mutation")
class CreateProductInput(
    @property:GraphQLDescription("An internal name to identify the Product, not visible to customers.")
    val internalName: String,
    @property:GraphQLDescription("If true, the Product is visible to customers.")
    val isPubliclyVisible: Boolean,
    @property:GraphQLDescription("The default ProductVariant of the Product.")
    val defaultVariant: ProductVariantInput,
    @property:GraphQLDescription("The Categories this product is associated with.")
    val categoryIds: List<UUID>,
)