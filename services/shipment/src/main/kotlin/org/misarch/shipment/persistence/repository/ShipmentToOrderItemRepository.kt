package org.misarch.shipment.persistence.repository

import org.misarch.shipment.persistence.model.ShipmentToOrderItemEntity
import org.misarch.shipment.persistence.model.ShipmentToOrderItemKey
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.UUID

/**
 * Repository for [ShipmentToOrderItemEntity]s
 */
interface ShipmentToOrderItemRepository : CoroutineCrudRepository<ShipmentToOrderItemEntity, ShipmentToOrderItemKey> {

    /**
     * Finds all [ShipmentToOrderItemEntity]s by the shipment id
     *
     * @param shipmentId the id of the shipment
     * @return the list of [ShipmentToOrderItemEntity]s
     */
    suspend fun findByKeyShipmentId(shipmentId: UUID): List<ShipmentToOrderItemEntity>

    /**
     * Deletes the [ShipmentToOrderItemEntity]s of a shipment
     *
     * @param shipmentId the id of the shipment
     * @return the number of deleted rows
     */
    suspend fun deleteByKeyShipmentId(shipmentId: UUID): Long

}
