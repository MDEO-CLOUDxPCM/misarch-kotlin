package org.misarch.shipment.persistence.repository

import org.misarch.shipment.persistence.model.ShipmentEntity
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.UUID

/**
 * Repository for [ShipmentEntity]s
 */
interface ShipmentRepository : CoroutineCrudRepository<ShipmentEntity, UUID> {

    /**
     * Find the [ShipmentEntity] of a return (fix: nullable; the original declared it non-null and failed with a
     * NullPointerException for a return without shipment).
     *
     * @param returnId the return ID
     * @return the [ShipmentEntity], or null if the return has no shipment
     */
    suspend fun findFirstByReturnId(returnId: UUID): ShipmentEntity?

    /**
     * Whether an order already has shipments (idempotent `payment-enabled` handling)
     *
     * @param orderId the order ID
     */
    suspend fun existsByOrderId(orderId: UUID): Boolean

    /**
     * Whether a return already has a shipment (idempotent `return/created` handling)
     *
     * @param returnId the return ID
     */
    suspend fun existsByReturnId(returnId: UUID): Boolean

    /**
     * Takes a transaction-scoped advisory lock on [key] (serializes the handling of redelivered events of one order or
     * return; released at commit or rollback)
     *
     * @param key the lock key, e.g. `order:<id>`
     */
    @Query("SELECT 1 FROM pg_advisory_xact_lock(hashtext(:key))")
    suspend fun lockForTransaction(key: String): Int

}
