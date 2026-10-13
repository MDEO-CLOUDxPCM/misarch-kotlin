package org.misarch.shipment.persistence.model

import org.misarch.common.persistence.BaseEntity
import org.misarch.shipment.event.model.ShipmentMethodDTO
import org.misarch.shipment.graphql.model.ShipmentMethod
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Entity for shipment methods
 *
 * @property name name of the shipment method
 * @property description description of the shipment method
 * @property externalReference reference of the shipment method used by the external shipment provider
 * @property baseFees base fees for the shipment method
 * @property feesPerItem fees per item for the shipment method
 * @property feesPerKg fees per kg for the shipment method
 * @property archivedAt time of archiving, null if not archived
 * @property id unique identifier of the shipment method
 */
@Table
class ShipmentMethodEntity(
    val name: String,
    val description: String,
    val externalReference: String,
    val baseFees: Int,
    val feesPerItem: Int,
    val feesPerKg: Int,
    var archivedAt: OffsetDateTime?,
    @Id
    override val id: UUID?
) : BaseEntity<ShipmentMethod> {

    override fun toDTO(): ShipmentMethod {
        return ShipmentMethod(
            id = id!!,
            name = name,
            description = description,
            externalReference = externalReference,
            baseFees = baseFees,
            feesPerItem = feesPerItem,
            feesPerKg = feesPerKg,
            archivedAt = archivedAt
        )
    }

    /**
     * Converts the entity to an event DTO
     *
     * @return the event DTO
     */
    fun toEventDTO(): ShipmentMethodDTO {
        return ShipmentMethodDTO(
            id = id!!,
            name = name,
            description = description,
            externalReference = externalReference,
            baseFees = baseFees,
            feesPerItem = feesPerItem,
            feesPerKg = feesPerKg
        )
    }

}
