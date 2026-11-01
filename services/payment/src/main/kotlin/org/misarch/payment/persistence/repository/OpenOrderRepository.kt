package org.misarch.payment.persistence.repository

import io.r2dbc.postgresql.codec.Json
import org.misarch.payment.persistence.model.OpenOrderEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.UUID

/**
 * Repository for [OpenOrderEntity]s
 */
interface OpenOrderRepository : CoroutineCrudRepository<OpenOrderEntity, UUID> {

    /**
     * Stores the order of a new payment, unless the order already has a payment (redelivered events)
     *
     * @param paymentId the new payment
     * @param orderId the order
     * @param orderJson the order as received
     * @return 1 if stored, 0 if the order already has a payment
     */
    @Modifying
    @Query(
        "INSERT INTO OpenOrderEntity (paymentId, orderId, orderJson) VALUES (:paymentId, :orderId, :orderJson) " +
            "ON CONFLICT (orderId) DO NOTHING"
    )
    suspend fun createOpenOrder(paymentId: UUID, orderId: UUID, orderJson: Json): Int

}
