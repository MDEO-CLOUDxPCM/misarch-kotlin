package org.misarch.invoice.event.model

import java.time.OffsetDateTime
import java.util.UUID

/**
 * DTO of a created invoice
 *
 * @property orderId the id of the order
 * @property issuedAt the date and time the invoice was issued
 * @property content the rendered invoice
 */
data class InvoiceDTO(
    val orderId: UUID,
    val issuedAt: OffsetDateTime,
    val content: String
)
