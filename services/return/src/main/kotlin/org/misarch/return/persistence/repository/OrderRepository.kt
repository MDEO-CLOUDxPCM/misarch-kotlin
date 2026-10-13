package org.misarch.`return`.persistence.repository

import org.misarch.`return`.persistence.model.OrderEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * Repository for [OrderEntity]s
 */
@Repository
interface OrderRepository : CoroutineCrudRepository<OrderEntity, UUID> {

    /**
     * Inserts the order replica; a redelivered event is a no-op (fix: the original's plain INSERT failed forever)
     */
    @Modifying
    @Query("INSERT INTO OrderEntity (id, userId) VALUES (:id, :userId) ON CONFLICT (id) DO NOTHING")
    suspend fun createOrder(
        @Param("id") id: UUID,
        @Param("userId") userId: UUID
    )

}
