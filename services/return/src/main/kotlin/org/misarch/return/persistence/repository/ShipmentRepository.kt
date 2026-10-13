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

    @Modifying
    @Query("INSERT INTO ShipmentEntity (id, deliveredAt) VALUES (:id, :deliveredAt)")
    suspend fun createShipment(
        @Param("id") id: UUID,
        @Param("deliveredAt") deliveredAt: OffsetDateTime?
    )

}
