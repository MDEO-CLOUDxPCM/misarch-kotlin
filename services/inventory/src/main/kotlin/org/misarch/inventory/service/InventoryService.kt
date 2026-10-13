package org.misarch.inventory.service

import io.r2dbc.spi.R2dbcException
import org.misarch.common.nest.JsError
import org.misarch.common.nest.NotFoundException
import org.misarch.inventory.persistence.FilterValue
import org.misarch.inventory.persistence.ProductItemRow
import org.misarch.inventory.persistence.ProductItemStore
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import org.springframework.transaction.support.DefaultTransactionDefinition

/** Product item statuses (`ProductItemStatus` of the original). */
object ProductItemStatuses {
    const val IN_STORAGE = "IN_STORAGE"
    const val RESERVED = "RESERVED"
    const val IN_FULFILLMENT = "IN_FULFILLMENT"
    const val SHIPPED = "SHIPPED"
    const val DELIVERED = "DELIVERED"
    const val LOST = "LOST"
}

/**
 * The original `InventoryService` + `ProductVariantPartialService` (docs/specs/inventory.md §4).
 */
@Service
class InventoryService(
    private val store: ProductItemStore,
    transactionManager: ReactiveTransactionManager,
) {
    private val logger = LoggerFactory.getLogger(InventoryService::class.java)

    private val transaction = TransactionalOperator.create(transactionManager)

    /** Mongo snapshot transaction → Postgres REPEATABLE READ (write conflicts fail instead of waiting forever). */
    private val snapshotTransaction = TransactionalOperator.create(
        transactionManager,
        DefaultTransactionDefinition().apply { isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ }
    )

    // ---- product variant partials ----

    suspend fun createPartial(id: String) = store.insertPartial(id)

    /** `findByIdOrFail` */
    suspend fun requireVariant(id: String) {
        if (!store.partialExists(id)) throw NotFoundException("ProductVariant with ID \"$id\" not found")
    }

    // ---- product items ----

    suspend fun createProductItemBatch(productVariantId: String, number: Int): List<ProductItemRow> {
        requireVariant(productVariantId)
        return transaction.executeAndAwait { store.insert(productVariantId, maxOf(number, 0)) }
    }

    suspend fun findById(id: String): ProductItemRow =
        store.findById(id) ?: throw NotFoundException("ProductItem with ID \"$id\" not found")

    suspend fun update(id: String, productVariantId: String, inventoryStatus: String): ProductItemRow {
        requireVariant(productVariantId)
        return store.update(id, productVariantId, inventoryStatus)
            ?: throw NotFoundException("ProductItem with ID \"$id\" not found")
    }

    suspend fun delete(id: String): ProductItemRow? = store.delete(id)

    suspend fun findAll(ascending: Boolean, first: Long, skip: Long) = store.findAll(ascending, first, skip)

    suspend fun count(productVariant: FilterValue, inventoryStatus: FilterValue) = store.count(productVariant, inventoryStatus)

    /**
     * Reserves [number] IN_STORAGE items of the variant for [orderId] in one snapshot transaction. Mongo `limit`
     * semantics: 0 = no limit, negative = |n|. Returns nothing useful (the original's reload query matches nothing).
     */
    suspend fun reserveProductItemBatch(productVariantId: String, number: Long, orderId: String) {
        try {
            snapshotTransaction.executeAndAwait {
                val limit = when {
                    number == 0L -> null
                    number < 0 -> -number
                    else -> number
                }
                val ids = store.findAvailable(productVariantId, limit)
                if (ids.size < number) {
                    throw NotFoundException("Not enough product items available for product variant \"$productVariantId\"")
                }
                store.reserve(ids, orderId)
            }
        } catch (e: Throwable) {
            if (isSerializationFailure(e)) {
                logger.error("{reserveProductItemBatch} write conflict for variant {}", productVariantId)
                throw JsError(WRITE_CONFLICT)
            }
            logger.error("{reserveProductItemBatch} {}", e.message)
            throw e
        }
    }

    /** Releases everything assigned to [orderId]; returns the released items (see phantom quirk). */
    suspend fun releaseProductItemBatch(orderId: String?): List<ProductItemRow> {
        logger.info("Releasing product items reserved for order: {{}}", orderId)
        val ids = store.findIdsByOrder(orderId)
        store.releaseOrInsertPhantom(ids)
        return store.findByIds(ids)
    }

    suspend fun updateOrderProductItemsStatus(orderId: String?, status: String) {
        logger.info("Updating product items status for order: {{}} to {{}}", orderId, status)
        store.updateStatusByOrder(orderId, status)
    }

    private fun isSerializationFailure(e: Throwable): Boolean {
        var current: Throwable? = e
        while (current != null) {
            if (current is R2dbcException && (current.sqlState == "40001" || current.sqlState == "40P01")) return true
            if (current is DataAccessException && current.message?.contains("could not serialize access") == true) return true
            current = current.cause
        }
        return false
    }

    companion object {
        /** MongoDB's message for a conflicting transaction (verified against the original). */
        const val WRITE_CONFLICT =
            "WriteConflict error: this operation conflicted with another operation. Please retry your operation or multi-document transaction."
    }
}
