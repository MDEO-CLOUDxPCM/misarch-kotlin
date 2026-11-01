package org.misarch.`return`.persistence.repository

import org.misarch.`return`.persistence.model.OrderEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.UUID

/**
 * Repository for [OrderEntity]s
 */
interface OrderRepository : CoroutineCrudRepository<OrderEntity, UUID> {

    /**
     * Inserts the order replica; a redelivered event is a no-op
     */
    @Modifying
    @Query("INSERT INTO OrderEntity (id, userId) VALUES (:id, :userId) ON CONFLICT (id) DO NOTHING")
    suspend fun createOrder(
        id: UUID,
        userId: UUID
    )

}
