package org.misarch.shipment.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.InaccessibleDirective
import com.expediagroup.graphql.server.operations.Query
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.graphql.authorizedUserOrNull
import org.misarch.common.graphql.load
import org.misarch.shipment.graphql.dataloader.ShipmentMethodDataLoader
import org.misarch.shipment.graphql.input.CalculateShipmentFeesInput
import org.misarch.shipment.graphql.model.ShipmentMethod
import org.misarch.shipment.graphql.model.connection.ShipmentMethodConnection
import org.misarch.shipment.graphql.model.connection.ShipmentMethodFilter
import org.misarch.shipment.graphql.model.connection.ShipmentMethodOrder
import org.misarch.shipment.service.ShipmentMethodService
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Defines GraphQL queries
 *
 * @property shipmentMethodService service for [ShipmentMethod]s
 * @property entityTemplate executes the connection queries
 */
@Component
class Query(
    private val shipmentMethodService: ShipmentMethodService,
    private val entityTemplate: R2dbcEntityTemplate,
) : Query {

    @GraphQLDescription("Get all shipmentMethods")
    suspend fun shipmentMethods(
        @GraphQLDescription("Number of items to return")
        first: Int? = null,
        @GraphQLDescription("Number of items to skip")
        skip: Int? = null,
        @GraphQLDescription("Ordering")
        orderBy: ShipmentMethodOrder? = null,
        @GraphQLDescription("Filtering")
        filter: ShipmentMethodFilter? = null,
        dfe: DataFetchingEnvironment
    ): ShipmentMethodConnection {
        return ShipmentMethodConnection(first, skip, filter, null, orderBy, entityTemplate, dfe.authorizedUserOrNull)
    }

    @GraphQLDescription("Get a shipmentMethod by id")
    fun shipmentMethod(
        @GraphQLDescription("The id of the shipmentMethod")
        id: UUID,
        dfe: DataFetchingEnvironment
    ): CompletableFuture<ShipmentMethod> {
        return dfe.load(ShipmentMethodDataLoader::class, id)
    }

    @GraphQLDescription("Calculates the fees for a collection of shipped items. Should only be called by the Order service")
    @InaccessibleDirective
    suspend fun calculateShipmentFees(
        @GraphQLDescription("The input for the calculateShipmentFees query.")
        input: CalculateShipmentFeesInput
    ): Int {
        return shipmentMethodService.calculateShipmentFees(input)
    }

}
