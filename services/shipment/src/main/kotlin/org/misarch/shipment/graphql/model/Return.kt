package org.misarch.shipment.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.annotations.GraphQLIgnore
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import org.misarch.common.graphql.Node
import org.misarch.shipment.persistence.repository.ShipmentRepository
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

@GraphQLDescription("A return one or more OrderItems.")
@KeyDirective(fields = FieldSet("id"))
class Return(
    id: UUID
) : Node(id) {

    @GraphQLDescription("The associated Shipment")
    suspend fun shipment(
        @GraphQLIgnore
        @Autowired
        shipmentRepository: ShipmentRepository
    ): Shipment {
        // fix: `… : null` (the convention for unknown ids) instead of the original's NullPointerException text
        return shipmentRepository.findFirstByReturnId(id)?.toDTO() ?: throw NoSuchElementException()
    }

}
