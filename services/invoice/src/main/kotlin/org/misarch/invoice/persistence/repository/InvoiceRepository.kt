package org.misarch.invoice.persistence.repository

import org.misarch.invoice.persistence.model.InvoiceEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.UUID

/**
 * Repository for [InvoiceEntity]s
 */
interface InvoiceRepository : CoroutineCrudRepository<InvoiceEntity, UUID> {

    /**
     * Whether the order has an invoice
     *
     * @param orderId the id of the order
     */
    suspend fun existsByOrderId(orderId: UUID): Boolean

}
