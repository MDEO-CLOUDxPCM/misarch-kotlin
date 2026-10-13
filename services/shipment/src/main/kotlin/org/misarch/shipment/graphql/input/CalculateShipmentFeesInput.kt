package org.misarch.shipment.graphql.input

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.InaccessibleDirective

@GraphQLDescription("Input for calculateShipmentFees query.")
@InaccessibleDirective
class CalculateShipmentFeesInput(
    @property:GraphQLDescription("The items to calculate the shipment fees for.")
    val items: List<ProductVariantVersionWithQuantityAndShipmentMethodInput>
)