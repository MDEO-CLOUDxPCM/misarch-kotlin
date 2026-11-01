package org.misarch.invoice.service

import org.misarch.common.event.EventPublisher
import org.misarch.common.persistence.ConditionQueries
import org.misarch.invoice.event.InvoiceEvents
import org.misarch.invoice.event.model.InvoiceCreatedDTO
import org.misarch.invoice.event.model.OrderDTO
import org.misarch.invoice.persistence.model.InvoiceEntity
import org.misarch.invoice.persistence.model.UserAddressEntity
import org.misarch.invoice.persistence.model.VendorAddressEntity
import org.misarch.invoice.persistence.repository.InvoiceRepository
import org.misarch.invoice.persistence.repository.UserAddressRepository
import org.misarch.invoice.persistence.repository.UserRepository
import org.misarch.invoice.persistence.repository.VendorAddressRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Service for [InvoiceEntity]s
 *
 * @param repository the repository for [InvoiceEntity]s
 * @param userAddressRepository the repository for user addresses
 * @param vendorAddressRepository the repository for vendor addresses
 * @param userRepository the repository for users
 * @param conditionQueries inserts invoices (their id is part of the content)
 * @param eventPublisher publishes created invoices
 */
@Service
class InvoiceService(
    private val repository: InvoiceRepository,
    private val userAddressRepository: UserAddressRepository,
    private val vendorAddressRepository: VendorAddressRepository,
    private val userRepository: UserRepository,
    private val conditionQueries: ConditionQueries,
    private val eventPublisher: EventPublisher
) {

    /**
     * Creates the invoice of a validated order and publishes `invoice/invoice/created`. The invoice address is a
     * user address, the customer's name that of the user (or else the addressee's name of the address), the vendor
     * address the first complete one. A replica that is missing fails the event (it is redelivered); an order that
     * already has its invoice is skipped.
     *
     * @param order the validated order
     */
    @Transactional
    suspend fun createInvoice(order: OrderDTO) {
        if (repository.existsByOrderId(order.id)) {
            return
        }
        val address = checkNotNull(userAddressRepository.findById(order.invoiceAddressId)) {
            "User address with id ${order.invoiceAddressId} does not exist"
        }
        val vendorAddress = checkNotNull(vendorAddressRepository.findCurrentVendorAddress()) {
            "No vendor address exists"
        }
        val user = userRepository.findById(order.userId)
        val firstName = user?.firstName ?: address.firstName
        val lastName = user?.lastName ?: address.lastName
        check(firstName != null && lastName != null) { "User with id ${order.userId} does not exist" }
        val id = UUID.randomUUID()
        val issuedAt = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS)
        val invoice = conditionQueries.insert(
            InvoiceEntity(
                orderId = order.id,
                issuedAt = issuedAt,
                content = content(id, issuedAt, order, "$firstName, $lastName", address, vendorAddress),
                userAddressId = address.id,
                vendorAddressId = vendorAddress.id,
                vatNumber = order.vatNumber,
                id = id
            )
        )
        eventPublisher.publishEvent(InvoiceEvents.INVOICE_CREATED, InvoiceCreatedDTO(order, invoice.toEventDTO()))
    }

    /** The invoice as Markdown. */
    private fun content(
        id: UUID,
        issuedAt: OffsetDateTime,
        order: OrderDTO,
        customerName: String,
        address: UserAddressEntity,
        vendorAddress: VendorAddressEntity
    ): String {
        val items = order.orderItems.joinToString("") {
            "| ${it.id} | ${it.productVariantId} | ${it.count} | ${it.compensatableAmount} |\n"
        }
        return "\n# Invoice\n\n" +
            "### Company information:\n" +
            "${vendorAddress.companyName ?: ""}\n" +
            "${vendorAddress.street1}, ${vendorAddress.street2}\n" +
            "${vendorAddress.city}, ${vendorAddress.country}\n\n" +
            "VAT number: ${order.vatNumber ?: "-"}\n\n" +
            "### Customer information:\n" +
            "ID: ${order.userId}\n" +
            "Name: $customerName\n" +
            "Address:\n" +
            "${address.companyName ?: ""}\n" +
            "${address.street1}, ${address.street2}\n" +
            "${address.city}, ${address.country}\n\n" +
            "### Invoice ID: $id, issued at: ${ISSUED_AT_FORMAT.format(issuedAt)} \n\n" +
            "Terms and conditions: $TERMS\n\n" +
            "---\n\n" +
            "Purchased items overview:\n\n" +
            "| Item UUID | Product variant UUID | count | Compensatable amount |\n" +
            "| --- | --- | --- | --- |\n" +
            "$items\n\n" +
            "---\n\n" +
            "Total compensatable amount: ${order.compensatableOrderAmount}\n"
    }

    companion object {
        private val ISSUED_AT_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")

        private const val TERMS =
            "This invoice is created according the the companies terms and conditions specified on the website."
    }

}
