package org.misarch.shipment.graphql.input

import com.expediagroup.graphql.generator.annotations.GraphQLDescription

@GraphQLDescription("Input for the createShipmentMethod mutation.")
class CreateShipmentMethodInput(
    @property:GraphQLDescription("The name of the shipment method.")
    val name: String,
    @property:GraphQLDescription("The description of the shipment method.")
    val description: String,
    @property:GraphQLDescription("The reference of the shipment method used by the external shipment provider.")
    val externalReference: String,
    @property:GraphQLDescription("The base fees for the shipment method.")
    val baseFees: Int,
    @property:GraphQLDescription("The fees per item for the shipment method.")
    val feesPerItem: Int,
    @property:GraphQLDescription("The fees per kg for the shipment method.")
    val feesPerKg: Int,
)