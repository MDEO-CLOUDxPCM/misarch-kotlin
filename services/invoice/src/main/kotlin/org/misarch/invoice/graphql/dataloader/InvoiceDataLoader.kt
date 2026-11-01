package org.misarch.invoice.graphql.dataloader

import org.misarch.common.graphql.dataloader.IdDataLoader
import org.misarch.invoice.graphql.model.Invoice
import org.misarch.invoice.persistence.model.InvoiceEntity
import org.misarch.invoice.persistence.repository.InvoiceRepository
import org.springframework.stereotype.Component

/**
 * Data loader for [Invoice]s
 */
@Component
class InvoiceDataLoader(
    repository: InvoiceRepository
) : IdDataLoader<Invoice, InvoiceEntity>(repository)
