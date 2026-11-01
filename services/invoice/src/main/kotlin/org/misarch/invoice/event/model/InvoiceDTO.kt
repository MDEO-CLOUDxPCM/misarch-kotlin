package org.misarch.invoice.event.model

import org.misarch.common.event.MillisecondTimestampSerializer
import tools.jackson.databind.annotation.JsonSerialize
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
    @get:JsonSerialize(using = MillisecondTimestampSerializer::class)
    val issuedAt: OffsetDateTime,
    val content: String
)
