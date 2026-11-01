package org.misarch.order.persistence.repository

import org.misarch.order.persistence.model.ShipmentMethodEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.UUID

/**
 * Repository for [ShipmentMethodEntity]s
 */
interface ShipmentMethodRepository : CoroutineCrudRepository<ShipmentMethodEntity, UUID> {

    /**
     * Creates a shipment method (replica); an existing one is kept
     *
     * @param id the id of the shipment method
     */
    @Modifying
    @Query("INSERT INTO ShipmentMethodEntity (id) VALUES (:id) ON CONFLICT (id) DO NOTHING")
    suspend fun createShipmentMethod(id: UUID)

}
