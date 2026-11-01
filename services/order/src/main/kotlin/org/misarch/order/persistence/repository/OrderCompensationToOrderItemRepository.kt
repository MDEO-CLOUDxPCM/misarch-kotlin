package org.misarch.order.persistence.repository

import org.misarch.order.persistence.model.OrderCompensationToOrderItemEntity
import org.misarch.order.persistence.model.OrderCompensationToOrderItemKey
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.UUID

/**
 * Repository for [OrderCompensationToOrderItemEntity]s
 */
interface OrderCompensationToOrderItemRepository :
    CoroutineCrudRepository<OrderCompensationToOrderItemEntity, OrderCompensationToOrderItemKey> {

    /**
     * Finds the compensated items of all compensations of an order
     *
     * @param orderId the id of the order
     */
    @Query(
        "SELECT j.* FROM OrderCompensationToOrderItemEntity j " +
            "INNER JOIN OrderCompensationEntity c ON c.id = j.orderCompensationId WHERE c.orderId = :orderId"
    )
    suspend fun findByOrderId(orderId: UUID): List<OrderCompensationToOrderItemEntity>

}
