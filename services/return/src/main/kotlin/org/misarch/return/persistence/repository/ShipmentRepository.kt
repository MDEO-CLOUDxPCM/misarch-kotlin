package org.misarch.`return`.persistence.repository

import org.misarch.`return`.persistence.model.ShipmentEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Repository for [ShipmentEntity]s
 */
@Repository
interface ShipmentRepository : CoroutineCrudRepository<ShipmentEntity, UUID> {

    /**
     * Inserts the shipment replica. Idempotent: an existing row (redelivery, or a DELIVERED status update that
     * arrived first) keeps its `deliveredAt` unless it has none yet.
     */
    @Modifying
    @Query(
        """
        INSERT INTO ShipmentEntity (id, deliveredAt) VALUES (:id, :deliveredAt)
        ON CONFLICT (id) DO UPDATE SET deliveredAt = COALESCE(ShipmentEntity.deliveredAt, EXCLUDED.deliveredAt)
        """
    )
    suspend fun createShipment(
        @Param("id") id: UUID,
        @Param("deliveredAt") deliveredAt: OffsetDateTime?
    )

    /**
     * Sets `deliveredAt` of an order shipment, inserting the row if `shipment/shipment/created` has not been
     * processed yet (topics are delivered independently).
     */
    @Modifying
    @Query(
        """
        INSERT INTO ShipmentEntity (id, deliveredAt) VALUES (:id, :deliveredAt)
        ON CONFLICT (id) DO UPDATE SET deliveredAt = EXCLUDED.deliveredAt
        """
    )
    suspend fun upsertDeliveredAt(
        @Param("id") id: UUID,
        @Param("deliveredAt") deliveredAt: OffsetDateTime
    )

    /**
     * Sets `deliveredAt` of a known shipment; no-op for an unknown one (e.g. a return shipment, never registered).
     */
    @Modifying
    @Query("UPDATE ShipmentEntity SET deliveredAt = :deliveredAt WHERE id = :id")
    suspend fun updateDeliveredAt(
        @Param("id") id: UUID,
        @Param("deliveredAt") deliveredAt: OffsetDateTime
    )

}
