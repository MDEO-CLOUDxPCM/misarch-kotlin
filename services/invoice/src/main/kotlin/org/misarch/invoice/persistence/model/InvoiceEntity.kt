package org.misarch.invoice.persistence.model

import org.misarch.common.persistence.BaseEntity
import org.misarch.invoice.event.model.InvoiceDTO
import org.misarch.invoice.graphql.model.Invoice
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Entity for invoices
 *
 * @property orderId the id of the order (one invoice per order)
 * @property issuedAt the date and time the invoice was issued
 * @property content the rendered invoice (Markdown)
 * @property userAddressId the id of the invoice address of the customer
 * @property vendorAddressId the id of the address of the vendor
 * @property vatNumber the optional VAT number of the customer
 * @property id unique identifier of the invoice (part of the content, so it is created by the service)
 */
@Table
class InvoiceEntity(
    val orderId: UUID,
    val issuedAt: OffsetDateTime,
    val content: String,
    val userAddressId: UUID,
    val vendorAddressId: UUID,
    val vatNumber: String?,
    @Id
    override val id: UUID
) : BaseEntity<Invoice> {

    override fun toDTO(): Invoice {
        return Invoice(
            id = id,
            orderId = orderId,
            issuedAt = issuedAt,
            content = content,
            userAddressId = userAddressId,
            vendorAddressId = vendorAddressId,
            vatNumber = vatNumber
        )
    }

    /**
     * Converts the entity to an event DTO
     *
     * @return the event DTO
     */
    fun toEventDTO(): InvoiceDTO {
        return InvoiceDTO(
            orderId = orderId,
            issuedAt = issuedAt.toInstant(),
            content = content
        )
    }

}
