package org.misarch.invoice.event.model

/**
 * DTO of `invoice/invoice/created`
 *
 * @property order the invoiced order
 * @property invoice the invoice
 */
data class InvoiceCreatedDTO(
    val order: OrderDTO,
    val invoice: InvoiceDTO
)
