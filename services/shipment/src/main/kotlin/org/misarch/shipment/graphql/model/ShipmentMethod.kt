package org.misarch.shipment.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.annotations.GraphQLIgnore
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.graphql.authorizedUser
import org.misarch.shipment.graphql.input.ProductVariantVersionWithQuantityInput
import org.misarch.shipment.service.ShipmentMethodService
import org.springframework.beans.factory.annotation.Autowired
import java.time.OffsetDateTime
import java.util.UUID

@GraphQLDescription("A shipment method supported by the external provider.")
@KeyDirective(fields = FieldSet("id"))
class ShipmentMethod(
    id: UUID,
    @property:GraphQLDescription("The name of the shipment method.")
    val name: String,
    @property:GraphQLDescription("The description of the shipment method.")
    val description: String,
    @property:GraphQLDescription("The base fees for the shipment method.")
    val baseFees: Int,
    @property:GraphQLDescription("The fees per item for the shipment method.")
    val feesPerItem: Int,
    @property:GraphQLDescription("The fees per kg for the shipment method.")
    val feesPerKg: Int,
    @property:GraphQLDescription("If this shipment method is archived, the datetime it was archived.")
    val archivedAt: OffsetDateTime?, private val externalReference: String
) : Node(id) {

    @GraphQLDescription("If true, this shipment method is archived and can no longer be used.")
    val isArchived: Boolean
        get() = archivedAt != null


    @GraphQLDescription("The reference of the shipment method used by the external shipment provider.")
    fun externalReference(dfe: DataFetchingEnvironment): String {
        dfe.authorizedUser.checkIsEmployee()
        return externalReference
    }

    @GraphQLDescription("Calculates the fees for a potential shipment.")
    suspend fun calculateFees(
        @GraphQLDescription("The input for the calculation.")
        items: List<ProductVariantVersionWithQuantityInput>,
        @GraphQLIgnore
        @Autowired
        shipmentMethodService: ShipmentMethodService
    ): Int {
        val (quantity, weight) = shipmentMethodService.calculateQuantityAndWeight(items)
        return baseFees + (quantity * feesPerItem) + (weight * feesPerKg).toInt()
    }

}
