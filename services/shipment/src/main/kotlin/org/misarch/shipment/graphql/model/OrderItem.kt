package org.misarch.shipment.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.graphql.load
import org.misarch.shipment.graphql.dataloader.ShipmentDataLoader
import java.util.UUID
import java.util.concurrent.CompletableFuture

@GraphQLDescription("An item in an order.")
@KeyDirective(fields = FieldSet("id"))
class OrderItem(
    id: UUID,
    private val sentWithId: UUID?
) : Node(id) {

    @GraphQLDescription("The shipment this order item was originally sent with.")
    fun sentWith(
        dfe: DataFetchingEnvironment
    ): CompletableFuture<Shipment?> {
        if (sentWithId == null) {
            return CompletableFuture.completedFuture(null)
        }
        return dfe.load(ShipmentDataLoader::class, sentWithId).thenApply<Shipment?> { it }
    }

}
