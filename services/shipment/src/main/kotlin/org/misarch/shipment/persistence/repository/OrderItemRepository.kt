package org.misarch.shipment.persistence.repository

import org.misarch.shipment.persistence.model.OrderItemEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * Repository for [OrderItemEntity]s
 */
@Repository
interface OrderItemRepository : CoroutineCrudRepository<OrderItemEntity, UUID> {

    /**
     * Create a new order item.
     * If the order item already exists, do nothing.
     *
     * @param id unique identifier of the order item
     * @param sentWithId unique identifier of the shipment the order item was sent with originally
     */
    @Modifying
    @Query(
        """
        INSERT INTO OrderItemEntity (id, sentWithId, productVariantVersionId, quantity)
        VALUES (:id, :sentWithId, :productVariantVersionId, :quantity) ON CONFLICT DO NOTHING
        """
    )
    suspend fun createOrderItem(
        @Param("id")
        id: UUID,
        @Param("sentWithId")
        sentWithId: UUID,
        @Param("productVariantVersionId")
        productVariantVersionId: UUID,
        @Param("quantity")
        quantity: Int
    ): Long

}
