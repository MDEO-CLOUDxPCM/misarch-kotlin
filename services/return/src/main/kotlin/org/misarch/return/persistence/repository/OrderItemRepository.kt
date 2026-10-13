package org.misarch.`return`.persistence.repository

import org.misarch.`return`.persistence.model.OrderItemEntity
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

    @Modifying
    @Query(
        """
        INSERT INTO OrderItemEntity (id, sentWithId, orderId)
        VALUES (:id, :sentWithId, :orderId)
        ON CONFLICT (id) DO UPDATE SET sentWithId = :sentWithId
        """
    )
    suspend fun upsertOrderItemFromShipment(
        @Param("id")
        id: UUID,
        @Param("sentWithId")
        sentWithId: UUID,
        @Param("orderId")
        orderId: UUID
    )

    @Modifying
    @Query(
        """
        INSERT INTO OrderItemEntity (id, compensatableAmount, orderId, productVariantVersionId)
        VALUES (:id, :compensatableAmount, :orderId, :productVariantVersionId)
        ON CONFLICT (id) DO UPDATE SET compensatableAmount = :compensatableAmount, productVariantVersionId = :productVariantVersionId
        """
    )
    suspend fun upsertOrderItemFromOrder(
        @Param("id")
        id: UUID,
        @Param("compensatableAmount")
        compensatableAmount: Long,
        @Param("orderId")
        orderId: UUID,
        @Param("productVariantVersionId")
        productVariantVersionId: UUID
    )

}
