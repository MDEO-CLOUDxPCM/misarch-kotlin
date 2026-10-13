package org.misarch.shipment.persistence.repository

import org.misarch.shipment.persistence.model.ShipmentToOrderItemEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * Repository for [ShipmentToOrderItemEntity]s
 */
@Repository
interface ShipmentToOrderItemRepository : CoroutineCrudRepository<ShipmentToOrderItemEntity, UUID> {

    /**
     * Finds all [ShipmentToOrderItemEntity]s by the shipment id
     *
     * @param shipmentId the shipment id
     * @return the list of [ShipmentToOrderItemEntity]s
     */
    suspend fun findByShipmentId(shipmentId: UUID): List<ShipmentToOrderItemEntity>

}
