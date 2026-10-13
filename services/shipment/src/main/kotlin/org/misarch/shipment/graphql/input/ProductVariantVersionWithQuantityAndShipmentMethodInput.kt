package org.misarch.shipment.graphql.input

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import java.util.*

@GraphQLDescription("Input for a product variant version with a quantity and shipment method.")
class ProductVariantVersionWithQuantityAndShipmentMethodInput(
    productVariantVersionId: UUID,
    quantity: Int,
    @property:GraphQLDescription("The id of the shipment method.")
    val shipmentMethodId: UUID
) : ProductVariantVersionWithQuantityInput(productVariantVersionId, quantity)
