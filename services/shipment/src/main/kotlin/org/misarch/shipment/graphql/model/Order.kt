package org.misarch.shipment.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.annotations.GraphQLIgnore
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.graphql.Node
import org.misarch.common.graphql.authorizedUserOrNull
import org.misarch.common.persistence.Condition
import org.misarch.shipment.graphql.model.connection.ShipmentConnection
import org.misarch.shipment.graphql.model.connection.ShipmentFilter
import org.misarch.shipment.graphql.model.connection.ShipmentOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import java.util.UUID

@GraphQLDescription("The Order of a user.")
@KeyDirective(fields = FieldSet("id"))
class Order(
    id: UUID
) : Node(id) {

    @GraphQLDescription("Get all associated Shipments")
    suspend fun shipments(
        @GraphQLDescription("Number of items to return")
        first: Int? = null,
        @GraphQLDescription("Number of items to skip")
        skip: Int? = null,
        @GraphQLDescription("Ordering")
        orderBy: ShipmentOrder? = null,
        @GraphQLDescription("Filtering")
        filter: ShipmentFilter? = null,
        @GraphQLIgnore
        @Autowired
        entityTemplate: R2dbcEntityTemplate,
        dfe: DataFetchingEnvironment
    ): ShipmentConnection {
        return ShipmentConnection(
            first,
            skip,
            filter,
            Condition.eq("orderId", id),
            orderBy,
            entityTemplate,
            dfe.authorizedUserOrNull
        )
    }

}
