package org.misarch.`return`.persistence.repository

import org.misarch.`return`.persistence.model.OrderItemEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.UUID

/**
 * Repository for [OrderItemEntity]s
 */
interface OrderItemRepository : CoroutineCrudRepository<OrderItemEntity, UUID> {

    /**
     * Stores the shipment of an order item (of a created shipment), creating the item if it is unknown yet
     */
    @Modifying
    @Query(
        """
        INSERT INTO OrderItemEntity (id, sentWithId, orderId)
        VALUES (:id, :sentWithId, :orderId)
        ON CONFLICT (id) DO UPDATE SET sentWithId = EXCLUDED.sentWithId
        """
    )
    suspend fun upsertOrderItemFromShipment(
        id: UUID,
        sentWithId: UUID,
        orderId: UUID
    )

    /**
     * Stores the amount and product variant version of an order item (of a created order), creating the item
     * if it is unknown yet
     */
    @Modifying
    @Query(
        """
        INSERT INTO OrderItemEntity (id, compensatableAmount, orderId, productVariantVersionId)
        VALUES (:id, :compensatableAmount, :orderId, :productVariantVersionId)
        ON CONFLICT (id) DO UPDATE SET compensatableAmount = EXCLUDED.compensatableAmount,
            productVariantVersionId = EXCLUDED.productVariantVersionId
        """
    )
    suspend fun upsertOrderItemFromOrder(
        id: UUID,
        compensatableAmount: Long,
        orderId: UUID,
        productVariantVersionId: UUID
    )

}
