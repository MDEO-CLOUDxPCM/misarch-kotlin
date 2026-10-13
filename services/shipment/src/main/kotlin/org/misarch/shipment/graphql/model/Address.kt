package org.misarch.shipment.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import java.util.*

@GraphQLDescription("An item in an order.")
abstract class Address(
    @property:GraphQLDescription("The ID of the node.")
    val id: UUID
)