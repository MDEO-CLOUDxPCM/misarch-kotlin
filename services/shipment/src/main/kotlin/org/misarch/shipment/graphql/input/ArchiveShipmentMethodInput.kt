package org.misarch.shipment.graphql.input

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import java.util.*

@GraphQLDescription("Input for the archiveShipmentMethod mutation.")
class ArchiveShipmentMethodInput(
    @property:GraphQLDescription("The id of the shipment method to archive.")
    val id: UUID
)