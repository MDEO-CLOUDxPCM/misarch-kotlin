package org.misarch.inventory.persistence.repository

import org.misarch.inventory.graphql.model.ProductItemStatus
import org.misarch.inventory.persistence.model.ProductItemEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.UUID

/**
 * Repository for [ProductItemEntity]s
 */
interface ProductItemRepository : CoroutineCrudRepository<ProductItemEntity, UUID> {

    /**
     * The number of product items of a product variant with an inventory status (`ProductVariant.inventoryCount`)
     *
     * @param productVariantId the product variant
     * @param inventoryStatus the inventory status
     */
    suspend fun countByProductVariantIdAndInventoryStatus(
        productVariantId: UUID, inventoryStatus: ProductItemStatus
    ): Long

    /**
     * The product items assigned to an order (any status)
     *
     * @param orderId the order
     */
    suspend fun findByOrderId(orderId: UUID): List<ProductItemEntity>

    /**
     * Reserves up to [number] product items in storage of a product variant for an order, the oldest first, and
     * returns them. `SKIP LOCKED`: concurrent reservations of the same variant take different items instead of
     * waiting for (and then conflicting with) each other. Uses the index (productVariantId, inventoryStatus, insertionOrder).
     *
     * @param productVariantId the product variant
     * @param number the maximum number of items to reserve
     * @param orderId the order that reserves the items
     */
    @Query(
        "UPDATE ProductItemEntity SET inventoryStatus = 'RESERVED', orderId = :orderId WHERE id IN (" +
            "SELECT id FROM ProductItemEntity WHERE productVariantId = :productVariantId " +
            "AND inventoryStatus = 'IN_STORAGE' ORDER BY insertionOrder LIMIT :number FOR UPDATE SKIP LOCKED) RETURNING *"
    )
    suspend fun reserve(productVariantId: UUID, number: Int, orderId: UUID): List<ProductItemEntity>

    /**
     * Puts all product items of an order back into storage
     *
     * @param orderId the order
     * @return the number of released items
     */
    @Modifying
    @Query("UPDATE ProductItemEntity SET inventoryStatus = 'IN_STORAGE', orderId = NULL WHERE orderId = :orderId")
    suspend fun releaseByOrderId(orderId: UUID): Int

    /**
     * Sets the inventory status of all product items of an order
     *
     * @param orderId the order
     * @param inventoryStatus the new inventory status
     * @return the number of updated items
     */
    @Modifying
    @Query("UPDATE ProductItemEntity SET inventoryStatus = :inventoryStatus WHERE orderId = :orderId")
    suspend fun updateInventoryStatusByOrderId(orderId: UUID, inventoryStatus: ProductItemStatus): Int

}
