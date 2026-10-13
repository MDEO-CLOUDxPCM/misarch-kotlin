package org.misarch.catalog.graphql.input

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import java.util.*

@GraphQLDescription("Input for the updateProduct mutation.")
class UpdateProductInput(
    @property:GraphQLDescription("UUID of the product to be updated")
    val id: UUID,
    @property:GraphQLDescription("If present, new value for isPubliclyVisible")
    val isPubliclyVisible: Boolean?,
    @property:GraphQLDescription("If present, new value for internalName")
    val internalName: String?,
    @property:GraphQLDescription("If present, new value for defaultVariantId")
    val defaultVariantId: UUID?
)