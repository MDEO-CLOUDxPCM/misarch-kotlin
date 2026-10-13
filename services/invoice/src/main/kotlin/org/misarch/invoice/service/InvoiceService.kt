package org.misarch.invoice.service

import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.misarch.common.event.DaprConfiguration
import org.misarch.common.event.DaprProperties
import org.misarch.common.graphql.GraphQLErrorException
import org.misarch.common.rust.RustDateTime
import org.misarch.invoice.event.OrderEventData
import org.misarch.invoice.event.UserAddressArchivedEventData
import org.misarch.invoice.event.UserAddressEventData
import org.misarch.invoice.event.UserEventData
import org.misarch.invoice.event.VendorAddressEventData
import org.misarch.invoice.graphql.model.Invoice
import org.misarch.invoice.graphql.model.UserAddress
import org.misarch.invoice.graphql.model.VendorAddress
import org.misarch.invoice.persistence.InvoiceRow
import org.misarch.invoice.persistence.InvoiceStore
import org.misarch.invoice.persistence.UserAddressRow
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Failure inside an event handler (→ HTTP 500, Dapr redelivers). */
class InvoiceEventException(message: String) : RuntimeException(message)

/**
 * Business logic of the invoice service (original `graphql/query.rs`, `graphql/model/invoice.rs`,
 * `event/http_event_service.rs`). Exact semantics: docs/specs/invoice.md.
 */
@Service
class InvoiceService(
    private val store: InvoiceStore,
    @Qualifier(DaprConfiguration.DAPR_WEB_CLIENT) private val webClient: WebClient,
    private val daprProperties: DaprProperties,
    transactionManager: ReactiveTransactionManager,
) {

    private val transactionalOperator = TransactionalOperator.create(transactionManager)

    // ---------------------------------------------------------------- GraphQL

    /** `query_object` on `invoices`: missing or undeserializable → not found. */
    suspend fun loadInvoice(id: UUID): Invoice =
        store.findInvoice(id)?.let(::toInvoice) ?: throw GraphQLErrorException("$INVOICE_TYPE with UUID: `$id` not found.")

    /** `query_invoice_by_order_id`: first invoice of the order (natural order). */
    suspend fun loadInvoiceByOrderId(orderId: UUID): Invoice =
        store.findInvoiceByOrderId(orderId)?.let(::toInvoice)
            ?: throw GraphQLErrorException("Invoice with order_id UUID: `$orderId` not found.")

    /** Deserializes the embedded addresses like serde (`UserAddress`/`VendorAddress` need all their fields). */
    private fun toInvoice(row: InvoiceRow): Invoice? {
        val userAddress = mapper.readTree(row.userAddress)
        val vendorAddress = mapper.readTree(row.vendorAddress)
        if (!hasStrings(userAddress, USER_ADDRESS_FIELDS) || !hasStrings(vendorAddress, VENDOR_ADDRESS_FIELDS)) return null
        return Invoice(
            id = row.id,
            orderId = row.orderId,
            issuedAt = row.issuedAt,
            content = row.content,
            userAddress = UserAddress(UUID.fromString(userAddress.get("_id").stringValue())),
            vendorAddress = VendorAddress(UUID.fromString(vendorAddress.get("_id").stringValue())),
            vatNumber = row.vatNumber,
        )
    }

    private fun hasStrings(node: JsonNode, fields: List<String>) = fields.all { node.get(it)?.isString == true }

    // ---------------------------------------------------------------- events

    /**
     * `on_discount_order_validation_succeeded_event` after deserialization and the topic check: `Invoice::new`, insert,
     * publish `invoice/invoice/created`. Throws [InvoiceEventException] (→ 500, Dapr redelivers) while a replica it
     * needs is missing.
     *
     * FIXED (docs/fixes/invoice.md): the original could never create an invoice - the payload never deserialized (D2),
     * the address lookup read a field the address events never wrote (D3) and dropped the names it then required (D6),
     * user events never arrived (D1), vendor addresses were never stored (D5) and the publish went to a non-existent
     * pub/sub component (D4). Now: the invoice address comes from the `user_address` replica, the customer name from
     * the user replica (fallback: the addressee's name of the address), the vendor address from the first complete
     * vendor address; one invoice per order (a redelivery after success is acknowledged without a second invoice); the
     * insert and the publish run in one transaction (a failed publish rolls the invoice back and the event is redelivered).
     */
    suspend fun createInvoice(order: OrderEventData) {
        if (store.findInvoiceByOrderId(order.id) != null) return
        val id = UUID.randomUUID()
        val issuedAt = RustDateTime.now()
        val items = renderItems(order)
        val address = store.findUserAddress(order.invoiceAddressId)
            ?: throw InvoiceEventException("Address of UUID: `${order.invoiceAddressId}` not found.")
        val vendor = store.findFirstVendorAddress() ?: throw InvoiceEventException("Vendor address is not set locally.")
        val user = store.findUser(order.userId)
        val firstName = user?.firstName ?: address.firstName
        val lastName = user?.lastName ?: address.lastName
        if (firstName == null || lastName == null) {
            throw InvoiceEventException("misarch_invoice::graphql::model::foreign_types::User with UUID: `${order.userId}` not found.")
        }
        val content = renderContent(
            vendorCompanyName = vendor.companyName ?: "", vendorStreet1 = vendor.street1!!, vendorStreet2 = vendor.street2!!,
            vendorCity = vendor.city!!, vendorCountry = vendor.country!!,
            vatNumber = order.vatNumber ?: "-",
            userId = order.userId, firstName = firstName, lastName = lastName,
            userAddressCompanyName = address.companyName ?: "",
            userAddressStreet1 = address.street1,
            userAddressStreet2 = address.street2,
            userAddressCity = address.city,
            userAddressCountry = address.country,
            invoiceId = id, issuedAt = issuedAt, items = items, compensatableOrderAmount = order.compensatableOrderAmount.toString(),
        )
        val userAddressJson = linkedMapOf(
            "_id" to address.id.toString(), "street1" to address.street1, "street2" to address.street2, "city" to address.city,
            "postal_code" to address.postalCode, "country" to address.country, "company_name" to (address.companyName ?: ""),
            "user_id" to address.userId.toString(),
        )
        val vendorJson = linkedMapOf(
            "_id" to vendor.id.toString(), "street1" to vendor.street1, "street2" to vendor.street2, "city" to vendor.city,
            "postal_code" to vendor.postalCode, "country" to vendor.country, "company_name" to (vendor.companyName ?: ""),
        )
        transactionalOperator.executeAndAwait {
            val inserted = store.insertInvoice(
                InvoiceRow(
                    id, order.id, issuedAt, content, mapper.writeValueAsString(userAddressJson),
                    mapper.writeValueAsString(vendorJson), order.vatNumber
                )
            )
            if (inserted) publishInvoiceCreated(invoiceCreatedJson(order, order.id, issuedAt, content))
        }
    }

    /**
     * Publishes `invoice/invoice/created` on the `pubsub` component. FIXED (D4): the original posted to the
     * non-existent component `invoice` (topic `invoice/created`) and ignored Dapr's error answer; a non-2xx answer or a
     * transport error now fails the handler.
     */
    suspend fun publishInvoiceCreated(json: String) {
        val status = try {
            webClient.post()
                .uri(URI.create("${daprProperties.httpEndpoint.trimEnd('/')}/v1.0/publish/${daprProperties.pubsubName}/$INVOICE_CREATED"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(json)
                .exchangeToMono { response -> response.releaseBody().thenReturn(response.statusCode().value()) }
                .awaitSingleOrNull()
        } catch (e: Exception) {
            throw InvoiceEventException("publish failed: ${e.message}")
        }
        if (status == null || status !in 200..299) throw InvoiceEventException("publish failed: HTTP $status")
    }

    /** FIXED (D5): stores the vendor address with all fields (the original stored only the id). */
    suspend fun onVendorAddressCreated(data: VendorAddressEventData) = store.upsertVendorAddress(
        data.id, data.street1, data.street2, data.city, data.postalCode, data.country, data.companyName
    )

    /** `add_user_to_mongodb`; FIXED: idempotent (a duplicate was a 500 forever). */
    suspend fun onUserCreated(data: UserEventData) = store.insertUser(data.id, data.firstName, data.lastName)

    /** FIXED (D3): the address is stored in `user_address` (independent of the user row), idempotently. */
    suspend fun onUserAddressCreated(data: UserAddressEventData) = store.insertUserAddress(
        UserAddressRow(
            data.id, data.userId, data.street1, data.street2, data.city, data.postalCode, data.country, data.companyName,
            data.firstName, data.lastName,
        )
    )

    /** FIXED: marks the address archived (the original's `$pull` path failed with 500 for any user with addresses). */
    suspend fun onUserAddressArchived(data: UserAddressArchivedEventData) = store.archiveUserAddress(data.id, data.userId)

    companion object {
        const val INVOICE_TYPE = "misarch_invoice::graphql::model::invoice::Invoice"
        const val INVOICE_CREATED = "invoice/invoice/created"
        const val INVOICE_TERMS =
            "This invoice is created according the the companies terms and conditions specified on the website."

        private val USER_ADDRESS_FIELDS = listOf("_id", "street1", "street2", "city", "postal_code", "country", "company_name", "user_id")
        private val VENDOR_ADDRESS_FIELDS = listOf("_id", "street1", "street2", "city", "postal_code", "country", "company_name")
        private val ISSUED_AT: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)

        /** Plain Jackson (nulls written, insertion order kept) - serde_json output. */
        val mapper: JsonMapper = JsonMapper.builder().build()

        /** `build_order_item_invoice_content`. */
        fun renderItems(order: OrderEventData): String = buildString {
            append("| Item UUID | Product variant UUID | count | Compensatable amount |\n")
            append("| --- | --- | --- | --- |\n")
            for (item in order.orderItems) {
                append("| ${item.id} | ${item.productVariantId} | ${item.count} | ${item.compensatableAmount} |\n")
            }
        }

        /** The `format!` template of `Invoice::new` (leading newline, trailing space after the issued-at value). */
        fun renderContent(
            vendorCompanyName: String, vendorStreet1: String, vendorStreet2: String, vendorCity: String, vendorCountry: String,
            vatNumber: String, userId: UUID, firstName: String, lastName: String,
            userAddressCompanyName: String, userAddressStreet1: String, userAddressStreet2: String,
            userAddressCity: String, userAddressCountry: String,
            invoiceId: UUID, issuedAt: Instant, items: String, compensatableOrderAmount: String,
        ): String =
            "\n# Invoice\n\n### Company information:\n$vendorCompanyName\n$vendorStreet1, $vendorStreet2\n" +
                "$vendorCity, $vendorCountry\n\nVAT number: $vatNumber\n\n### Customer information:\nID: $userId\n" +
                "Name: $firstName, $lastName\nAddress:\n$userAddressCompanyName\n$userAddressStreet1, $userAddressStreet2\n" +
                "$userAddressCity, $userAddressCountry\n\n### Invoice ID: $invoiceId, issued at: ${ISSUED_AT.format(issuedAt)} \n\n" +
                "Terms and conditions: $INVOICE_TERMS\n\n---\n\nPurchased items overview:\n\n$items\n\n---\n\n" +
                "Total compensatable amount: $compensatableOrderAmount\n"

        /**
         * `InvoiceCreatedDTO` (struct field order, `null`s, chrono serde `Z` timestamps). FIXED: the enum values are the
         * Jackson names used by all other services (`PLACED`) and `paymentAuthorization` is `{"cvc": n}`.
         */
        fun invoiceCreatedJson(order: OrderEventData, orderId: UUID, issuedAt: Instant, content: String): String {
            val orderJson = linkedMapOf<String, Any?>(
                "id" to order.id.toString(),
                "userId" to order.userId.toString(),
                "createdAt" to RustDateTime.toSerde(order.createdAt),
                "orderStatus" to order.orderStatus,
                "placedAt" to RustDateTime.toSerde(order.placedAt),
                "rejectionReason" to order.rejectionReason,
                "orderItems" to order.orderItems.map { item ->
                    linkedMapOf<String, Any?>(
                        "id" to item.id.toString(),
                        "createdAt" to RustDateTime.toSerde(item.createdAt),
                        "productVariantId" to item.productVariantId.toString(),
                        "productVariantVersionId" to item.productVariantVersionId.toString(),
                        "taxRateVersionId" to item.taxRateVersionId.toString(),
                        "shoppingCartItemId" to item.shoppingCartItemId.toString(),
                        "count" to item.count,
                        "compensatableAmount" to item.compensatableAmount,
                        "shipmentMethodId" to item.shipmentMethodId.toString(),
                        "discountIds" to item.discountIds.map(UUID::toString),
                    )
                },
                "shipmentAddressId" to order.shipmentAddressId.toString(),
                "invoiceAddressId" to order.invoiceAddressId.toString(),
                "compensatableOrderAmount" to order.compensatableOrderAmount,
                "paymentInformationId" to order.paymentInformationId.toString(),
                "paymentAuthorization" to order.paymentAuthorizationCvc?.let { mapOf("cvc" to it) },
                "vatNumber" to order.vatNumber,
            )
            val invoiceJson = linkedMapOf<String, Any?>(
                "orderId" to orderId.toString(),
                "issuedAt" to RustDateTime.toSerde(issuedAt),
                "content" to content,
            )
            return mapper.writeValueAsString(linkedMapOf("order" to orderJson, "invoice" to invoiceJson))
        }
    }
}
