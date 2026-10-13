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
) {

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
     * publish. Throws [InvoiceEventException] (→ 500). In practice the address lookup always fails (D3, D6).
     */
    suspend fun createInvoice(order: OrderEventData) {
        val id = UUID.randomUUID()
        val issuedAt = RustDateTime.now()
        val items = renderItems(order)
        // query_user_address_user + project_user_to_user_address
        val projected = store.findUserByAddress(order.invoiceAddressId)
            ?: throw InvoiceEventException("Address of UUID: `${order.invoiceAddressId}` not found.")
        // D6: the projection {"addresses.$": 1, "_id": 1} carries no first_name/last_name, which `User` requires;
        // serde fails here for every possible document
        projected.firstName ?: throw InvoiceEventException("missing field `first_name`")
        projected.lastName ?: throw InvoiceEventException("missing field `last_name`")
        val userAddress = mapper.readTree(projected.matchingAddress)
        // query_vendor_address
        val vendor = store.findFirstVendorAddress() ?: throw InvoiceEventException("Vendor address is not set locally.")
        if (listOf(vendor.street1, vendor.street2, vendor.city, vendor.postalCode, vendor.country, vendor.companyName).any { it == null }) {
            throw InvoiceEventException("missing field in vendor address")
        }
        val user = store.findUser(order.userId)
            ?: throw InvoiceEventException("misarch_invoice::graphql::model::foreign_types::User with UUID: `${order.userId}` not found.")
        val content = renderContent(
            vendorCompanyName = vendor.companyName!!, vendorStreet1 = vendor.street1!!, vendorStreet2 = vendor.street2!!,
            vendorCity = vendor.city!!, vendorCountry = vendor.country!!,
            vatNumber = order.vatNumber ?: "-",
            userId = user.id, firstName = user.firstName, lastName = user.lastName,
            userAddressCompanyName = userAddress.get("company_name").stringValue(),
            userAddressStreet1 = userAddress.get("street1").stringValue(),
            userAddressStreet2 = userAddress.get("street2").stringValue(),
            userAddressCity = userAddress.get("city").stringValue(),
            userAddressCountry = userAddress.get("country").stringValue(),
            invoiceId = id, issuedAt = issuedAt, items = items, compensatableOrderAmount = order.compensatableOrderAmount.toString(),
        )
        val vendorJson = linkedMapOf(
            "_id" to vendor.id.toString(), "street1" to vendor.street1, "street2" to vendor.street2, "city" to vendor.city,
            "postal_code" to vendor.postalCode, "country" to vendor.country, "company_name" to vendor.companyName,
        )
        store.insertInvoice(
            InvoiceRow(id, order.id, issuedAt, content, projected.matchingAddress, mapper.writeValueAsString(vendorJson), order.vatNumber)
        )
        publishInvoiceCreated(invoiceCreatedJson(order, order.id, issuedAt, content))
    }

    /** `send_invoice_created_event`: `POST /v1.0/publish/invoice/invoice/created`; any HTTP answer is OK (D4). */
    suspend fun publishInvoiceCreated(json: String) {
        try {
            webClient.post()
                .uri(URI.create("${daprProperties.httpEndpoint.trimEnd('/')}/v1.0/publish/invoice/invoice/created"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(json)
                .exchangeToMono { it.releaseBody() }
                .awaitSingleOrNull()
        } catch (e: Exception) {
            throw InvoiceEventException("publish failed: ${e.message}")
        }
    }

    /** `create_or_update_vendor_address_in_mongodb`: upsert of the id only. */
    suspend fun onVendorAddressCreated(data: VendorAddressEventData) = store.upsertVendorAddressId(data.id)

    /** `add_user_to_mongodb` (duplicate → exception → 500). */
    suspend fun onUserCreated(data: UserEventData) = store.insertUser(data.id, data.firstName, data.lastName)

    /** `insert_user_address_in_mongodb`: `$push` onto `user_addresses` (D3). */
    suspend fun onUserAddressCreated(data: UserAddressEventData) {
        val address = linkedMapOf(
            "_id" to data.id.toString(), "street1" to data.street1, "street2" to data.street2, "city" to data.city,
            "postal_code" to data.postalCode, "country" to data.country, "company_name" to (data.companyName ?: ""),
            "user_id" to data.userId.toString(),
        )
        store.pushUserAddress(data.userId, mapper.writeValueAsString(address))
    }

    /**
     * `remove_user_address_in_mongodb`: `$pull: {"user_addresses._id": id}`. MongoDB rejects traversing the existing
     * array `user_addresses` with the field `_id` (→ 500); no user / no such field → no-op.
     */
    suspend fun onUserAddressArchived(data: UserAddressArchivedEventData) {
        val user = store.findUser(data.userId) ?: return
        if (user.userAddresses != null) {
            throw InvoiceEventException("Cannot apply \$pull: path user_addresses._id traverses an array")
        }
    }

    companion object {
        const val INVOICE_TYPE = "misarch_invoice::graphql::model::invoice::Invoice"
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

        /** `InvoiceCreatedDTO` as serde_json writes it (struct field order, `null`s, chrono serde `Z` timestamps). */
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
                "paymentAuthorization" to order.paymentAuthorizationCvc?.let { mapOf("cVC" to it) },
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
