package org.misarch.shipment.persistence.model

import org.misarch.common.persistence.BaseEntity
import org.misarch.shipment.graphql.model.Shipment
import org.misarch.shipment.graphql.model.ShipmentStatus
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

/**
 * Entity for shipments
 *
 * @property status status of the shipment
 * @property shipmentMethodId unique identifier of the shipment method this shipment uses
 * @property shipmentAddressId unique identifier of the address this shipment is sent to
 * @property orderId unique identifier of the order, null if the shipment is caused by a return
 * @property returnId unique identifier of the return, null if the shipment is caused by an order
 * @property id unique identifier of the shipment
 */
@Table
class ShipmentEntity(
    var status: ShipmentStatus,
    val shipmentMethodId: UUID,
    val shipmentAddressId: UUID,
    val orderId: UUID?,
    val returnId: UUID?,
    @Id
    override val id: UUID?
) : BaseEntity<Shipment> {

    override fun toDTO(): Shipment {
        return Shipment(
            id = id!!,
            status = status,
            shipmentMethodId = shipmentMethodId,
            shipmentAddressId = shipmentAddressId,
            orderID = orderId,
            returnID = returnId
        )
    }

}
