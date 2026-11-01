package org.misarch.shipment.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.server.operations.Mutation
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.graphql.authorizedUser
import org.misarch.shipment.graphql.input.ArchiveShipmentMethodInput
import org.misarch.shipment.graphql.input.CreateShipmentMethodInput
import org.misarch.shipment.graphql.model.ShipmentMethod
import org.misarch.shipment.service.ShipmentMethodService
import org.springframework.stereotype.Component

/**
 * Defines GraphQL mutations
 *
 * @property shipmentMethodService service used to create and update shipment methods
 */
@Component
class Mutation(
    private val shipmentMethodService: ShipmentMethodService,
) : Mutation {

    @GraphQLDescription("Create a new shipment method")
    suspend fun createShipmentMethod(
        @GraphQLDescription("Input for the createShipmentMethod mutation")
        input: CreateShipmentMethodInput,
        dfe: DataFetchingEnvironment
    ): ShipmentMethod {
        dfe.authorizedUser.checkIsEmployee()
        val shipmentMethod = shipmentMethodService.createShipmentMethod(input)
        return shipmentMethod.toDTO()
    }

    @GraphQLDescription("Archive a shipment method")
    suspend fun archiveShipmentMethod(
        @GraphQLDescription("Input for the archiveShipmentMethod mutation")
        input: ArchiveShipmentMethodInput,
        dfe: DataFetchingEnvironment
    ): ShipmentMethod {
        dfe.authorizedUser.checkIsEmployee()
        val shipmentMethod = shipmentMethodService.archiveShipmentMethod(input)
        return shipmentMethod.toDTO()
    }
}
