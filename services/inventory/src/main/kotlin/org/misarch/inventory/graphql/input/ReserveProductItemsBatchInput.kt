package org.misarch.inventory.graphql.input

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import java.util.UUID

@GraphQLDescription("The input to reserve a batch of product items")
class ReserveProductItemsBatchInput(
    @property:GraphQLDescription("The product variant id of the product item")
    val productVariantId: UUID,
    @property:GraphQLDescription("The number of product items to reserve")
    val number: Int,
    @property:GraphQLDescription("The order id that reserves the product items")
    val orderId: UUID
)
