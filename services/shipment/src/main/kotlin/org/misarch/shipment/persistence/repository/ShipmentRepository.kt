package org.misarch.shipment.persistence.repository

import org.misarch.shipment.persistence.model.ShipmentEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * Repository for [ShipmentEntity]s
 */
@Repository
interface ShipmentRepository : CoroutineCrudRepository<ShipmentEntity, UUID> {

    /**
     * Find a [ShipmentEntity] by its return ID.
     * Declared non-null like in the original although there may be no row: the caller then fails with the same
     * NullPointerException as the original (`Cannot invoke "...ShipmentEntity.toDTO()"`).
     *
     * @param returnId the return ID
     * @return the [ShipmentEntity]
     */
    suspend fun findByReturnId(returnId: UUID): ShipmentEntity

}
