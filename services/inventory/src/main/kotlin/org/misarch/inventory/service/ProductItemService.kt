package org.misarch.inventory.service

import kotlinx.coroutines.flow.toList
import org.misarch.common.persistence.findByIdOrThrow
import org.misarch.inventory.event.model.ShipmentDTO
import org.misarch.inventory.event.model.ShipmentStatus
import org.misarch.inventory.graphql.input.CreateProductItemBatchInput
import org.misarch.inventory.graphql.input.ReserveProductItemsBatchInput
import org.misarch.inventory.graphql.input.UpdateProductItemInput
import org.misarch.inventory.graphql.model.ProductItemStatus
import org.misarch.inventory.persistence.model.ProductItemEntity
import org.misarch.inventory.persistence.repository.ProductItemRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Service for [ProductItemEntity]s
 *
 * @property repository the product item repository
 * @property productVariantService checks the product variants of new and updated items
 */
@Service
class ProductItemService(
    private val repository: ProductItemRepository,
    private val productVariantService: ProductVariantService,
) {

    private val logger = LoggerFactory.getLogger(ProductItemService::class.java)

    /**
     * Creates `number` product items in storage of a known product variant (none for `number <= 0`)
     *
     * @param input the product variant and the number of items
     * @return the created items
     */
    @Transactional
    suspend fun createProductItemBatch(input: CreateProductItemBatchInput): List<ProductItemEntity> {
        productVariantService.requireProductVariant(input.productVariantId)
        val productItems = (0 until input.number).map {
            ProductItemEntity(
                productVariantId = input.productVariantId,
                inventoryStatus = ProductItemStatus.IN_STORAGE,
                orderId = null,
                id = null
            )
        }
        return repository.saveAll(productItems).toList()
    }

    /**
     * Updates the product variant and inventory status of a product item (the order is kept)
     *
     * @param input the product item and its new values
     * @return the updated item
     */
    @Transactional
    suspend fun updateProductItem(input: UpdateProductItemInput): ProductItemEntity {
        productVariantService.requireProductVariant(input.productVariantId)
        val productItem = repository.findByIdOrThrow(input.id)
        productItem.productVariantId = input.productVariantId
        productItem.inventoryStatus = input.inventoryStatus
        return repository.save(productItem)
    }

    /**
     * Deletes a product item
     *
     * @param id the id of the product item
     * @return the deleted item
     */
    @Transactional
    suspend fun deleteProductItem(id: UUID): ProductItemEntity {
        val productItem = repository.findByIdOrThrow(id)
        repository.delete(productItem)
        return productItem
    }

    /**
     * Reserves `number` product items in storage of a product variant for an order, all or nothing
     * (none for `number <= 0`)
     *
     * @param input the product variant, the number of items and the order
     * @return the reserved items, ordered by id
     * @throws IllegalStateException if not enough items are in storage (nothing is reserved then)
     */
    @Transactional
    suspend fun reserveProductItemBatch(input: ReserveProductItemsBatchInput): List<ProductItemEntity> {
        if (input.number <= 0) {
            return emptyList()
        }
        val reserved = repository.reserve(input.productVariantId, input.number, input.orderId)
        check(reserved.size == input.number) {
            "Not enough product items available for product variant \"${input.productVariantId}\""
        }
        return reserved.sortedBy { it.id }
    }

    /**
     * Puts all product items of an order back into storage
     *
     * @param orderId the order
     */
    suspend fun releaseProductItemsOfOrder(orderId: UUID) {
        logger.info("Releasing product items reserved for order {}", orderId)
        repository.releaseByOrderId(orderId)
    }

    /**
     * The payment of an order is enabled: its product items go into fulfillment
     *
     * @param orderId the order
     */
    suspend fun markProductItemsOfOrderInFulfillment(orderId: UUID) {
        repository.updateInventoryStatusByOrderId(orderId, ProductItemStatus.IN_FULFILLMENT)
    }

    /**
     * A shipment was created: the product items of its order are shipped (nothing for a return shipment)
     *
     * @param shipmentDTO the created shipment
     */
    suspend fun markProductItemsOfShipmentShipped(shipmentDTO: ShipmentDTO) {
        val orderId = shipmentDTO.orderId ?: return
        repository.updateInventoryStatusByOrderId(orderId, ProductItemStatus.SHIPPED)
    }

    /**
     * The status of a shipment changed: the product items of its order are delivered or lost
     * (nothing for a return shipment or another status)
     *
     * @param shipmentDTO the updated shipment
     */
    suspend fun updateProductItemsOfShipment(shipmentDTO: ShipmentDTO) {
        val orderId = shipmentDTO.orderId ?: return
        val inventoryStatus = when (shipmentDTO.status) {
            ShipmentStatus.DELIVERED -> ProductItemStatus.DELIVERED
            ShipmentStatus.FAILED -> ProductItemStatus.LOST
            else -> return
        }
        repository.updateInventoryStatusByOrderId(orderId, inventoryStatus)
    }


}
