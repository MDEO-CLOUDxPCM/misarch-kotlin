package org.misarch.inventory.graphql.input

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import org.misarch.inventory.graphql.model.ProductItemStatus
import java.util.UUID

@GraphQLDescription("The input of a product item update")
class UpdateProductItemInput(
    @property:GraphQLDescription("The product item identifier")
    val id: UUID,
    @property:GraphQLDescription("The product variant id of the product item")
    val productVariantId: UUID,
    @property:GraphQLDescription("The inventory state of the product item")
    val inventoryStatus: ProductItemStatus
)
