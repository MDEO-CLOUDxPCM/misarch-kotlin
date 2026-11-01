package org.misarch.inventory.graphql.input

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import java.util.UUID

@GraphQLDescription("The input of a product item batch creation")
class CreateProductItemBatchInput(
    @property:GraphQLDescription("The product variant id of the product item")
    val productVariantId: UUID,
    @property:GraphQLDescription("The number of products to add")
    val number: Int
)
