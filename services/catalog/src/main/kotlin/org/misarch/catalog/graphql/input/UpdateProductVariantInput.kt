package org.misarch.catalog.graphql.input

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import java.util.UUID

@GraphQLDescription("Input for the updateProductVariant mutation.")
class UpdateProductVariantInput(
    @property:GraphQLDescription("UUID of the product variant to be updated")
    val id: UUID,
    @property:GraphQLDescription("If present, new value for isPubliclyVisible")
    val isPubliclyVisible: Boolean?
)