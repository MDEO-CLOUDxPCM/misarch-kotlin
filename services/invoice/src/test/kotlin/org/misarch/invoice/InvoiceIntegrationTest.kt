package org.misarch.invoice

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.misarch.common.event.DaprProperties
import org.misarch.common.testing.MisarchIntegrationTest
import org.misarch.invoice.event.OrderEventData
import org.misarch.invoice.persistence.InvoiceStore
import org.misarch.invoice.service.InvoiceEventException
import org.misarch.invoice.service.InvoiceService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitOneOrNull
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.web.reactive.function.client.WebClient
import tools.jackson.databind.JsonNode
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Equivalence tests of docs/specs/invoice.md §9 (Postgres via Testcontainers, fake Dapr sidecar). */
class InvoiceIntegrationTest : MisarchIntegrationTest() {

    override val graphqlPath: String = "/"

    @Autowired
    lateinit var db: DatabaseClient

    @Autowired
    lateinit var invoiceService: InvoiceService

    @Autowired
    lateinit var store: InvoiceStore

    private fun error(result: JsonNode): String = result.path("errors").path(0).path("message").asString()

    private fun rawPost(path: String, body: String, contentType: String? = "application/cloudevents+json"): Int =
        webTestClient.post().uri(path)
            .apply { if (contentType != null) header("Content-Type", contentType) }
            .bodyValue(body).exchange().returnResult(String::class.java).status.value()

    /** The order payload discount publishes (Jackson: `PLACED`, `{"cvc": n}`). */
    private fun discountOrder(invoiceAddress: UUID = UUID.randomUUID(), user: UUID = UUID.randomUUID(),
                              status: String = "PLACED", payment: String = """{"cvc":123}""") = """
        {"order":{"id":"${UUID.randomUUID()}","userId":"$user","createdAt":"2026-10-13T10:00:00.123Z",
         "orderStatus":"$status","placedAt":"2026-10-13T10:00:01Z","rejectionReason":null,
         "orderItems":[{"id":"${UUID.randomUUID()}","createdAt":"2026-10-13T10:00:00Z","productVariantVersionId":"${UUID.randomUUID()}",
           "productVariantId":"${UUID.randomUUID()}","taxRateVersionId":"${UUID.randomUUID()}","shoppingCartItemId":"${UUID.randomUUID()}",
           "count":2,"compensatableAmount":40,"shipmentMethodId":"${UUID.randomUUID()}","discountIds":[]}],
         "shipmentAddressId":"$invoiceAddress","invoiceAddressId":"$invoiceAddress","compensatableOrderAmount":40,
         "paymentInformationId":"${UUID.randomUUID()}","paymentAuthorization":$payment,"vatNumber":"AB1234"}}
    """.trimIndent()

    private fun invoiceCount(): Long = runBlocking {
        db.sql("SELECT COUNT(*) AS c FROM invoices").map { row -> row.get("c", java.lang.Long::class.java)!!.toLong() }
            .awaitOneOrNull()!!
    }

    private fun userAddressesOf(id: UUID): Pair<String, String?>? = runBlocking {
        db.sql("SELECT addresses::text AS a, user_addresses::text AS u FROM users WHERE id = :id").bind("id", id)
            .map { row -> row.get("a", String::class.java)!! to row.get("u", String::class.java) }.awaitOneOrNull()
    }

    // ------------------------------------------------------------------ Dapr

    @Test
    fun `dapr subscribe equals the original, the user route is not served`() {
        val body = webTestClient.get().uri("/dapr/subscribe").exchange().expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!
        val reference = File(System.getProperty("misarch.reference.dir"), "dapr-subscribe/invoice.json").readText()
        assertEquals(reference.trim(), body.trim())
        assertEquals(404, deliverEvent("/on-id-creation-event", "user/user/created", """{"id":"${UUID.randomUUID()}","firstName":"a","lastName":"b"}"""))
        webTestClient.get().uri("/health").exchange().expectStatus().isOk.expectBody().isEmpty()
    }

    @Test
    fun `validation-succeeded with the real discount payload answers 422 (defect D2)`() {
        val before = invoiceCount()
        assertEquals(422, deliverEvent("/on-discount-validation-succeded", "discount/order/validation-succeeded", discountOrder()))
        // also with Pascal-case status, the `cvc` key alone fails
        assertEquals(422, deliverEvent("/on-discount-validation-succeded", "discount/order/validation-succeeded", discountOrder(status = "Placed")))
        assertEquals(before, invoiceCount())
        assertTrue(dapr.published.isEmpty())
    }

    @Test
    fun `deserializable validation-succeeded payloads always end in 500 (defects D3, D6)`() {
        val address = UUID.randomUUID()
        val user = UUID.randomUUID()
        val payload = discountOrder(address, user, status = "Placed", payment = """{"cVC":123}""")
        assertEquals(500, deliverEvent("/on-discount-validation-succeded", "discount/order/validation-succeeded", payload))
        // even with a fully seeded database (user whose `addresses` holds the invoice address, complete vendor address)
        runBlocking {
            db.sql(
                "INSERT INTO users (id, first_name, last_name, addresses) VALUES (:id, 'Ada', 'Lovelace', " +
                    "jsonb_build_array(jsonb_build_object('_id', :address, 'street1', 's1', 'street2', 's2', 'city', 'c', " +
                    "'postal_code', 'p', 'country', 'DE', 'company_name', '', 'user_id', :id2)))"
            ).bind("id", user).bind("address", address.toString()).bind("id2", user.toString()).fetch().awaitRowsUpdated()
            db.sql("INSERT INTO vendor_address VALUES (:id, 'v1', 'v2', 'vc', 'vp', 'DE', 'Vendor')").bind("id", UUID.randomUUID())
                .fetch().awaitRowsUpdated()
        }
        val before = invoiceCount()
        assertEquals(500, deliverEvent("/on-discount-validation-succeded", "discount/order/validation-succeeded", payload))
        assertEquals(before, invoiceCount())
        assertTrue(dapr.published.isEmpty())
        // wrong topic
        assertEquals(500, deliverEvent("/on-discount-validation-succeded", "discount/order/validation-failed", payload))
    }

    @Test
    fun `vendor address events - camelCase 422 (defect D5), snake_case stores only the id`() {
        val id = UUID.randomUUID()
        assertEquals(
            422,
            deliverEvent("/on-vendor-address-creation-event", "address/vendor-address/created",
                """{"id":"$id","name":null,"street1":"a","street2":"b","city":"c","postalCode":"p","country":"DE","companyName":"V"}""")
        )
        val snake = """{"id":"$id","street1":"a","street2":"b","city":"c","postal_code":"p","country":"DE","company_name":"V"}"""
        assertEquals(200, deliverEvent("/on-vendor-address-creation-event", "address/vendor-address/created", snake))
        assertEquals(200, deliverEvent("/on-vendor-address-creation-event", "address/vendor-address/created", snake))
        val row = runBlocking {
            db.sql("SELECT street1, company_name FROM vendor_address WHERE id = :id").bind("id", id)
                .map { r -> listOf(r.get("street1", String::class.java), r.get("company_name", String::class.java)) }.awaitOneOrNull()
        }
        assertEquals(listOf(null, null), row)
        assertEquals(500, deliverEvent("/on-vendor-address-creation-event", "address/user-address/created", snake))
    }

    @Test
    fun `user creation route expects snake_case, duplicates answer 500`() {
        val id = UUID.randomUUID()
        assertEquals(422, deliverEvent("/on-user-creation-event", "user/user/created", """{"id":"$id","firstName":"A","lastName":"B"}"""))
        assertEquals(200, deliverEvent("/on-user-creation-event", "user/user/created", """{"id":"$id","first_name":"A","last_name":"B"}"""))
        assertEquals("[]" to null, userAddressesOf(id))
        assertEquals(500, deliverEvent("/on-user-creation-event", "user/user/created", """{"id":"$id","first_name":"A","last_name":"B"}"""))
    }

    @Test
    fun `user address events push to user_addresses (defect D3) and archive fails on existing arrays`() {
        val unknown = UUID.randomUUID()
        fun address(user: UUID, company: String = "null") =
            """{"id":"${UUID.randomUUID()}","name":{"firstName":"A","lastName":"B"},"street1":"s1","street2":"s2","city":"c","postalCode":"p","country":"DE","companyName":$company,"userId":"$user"}"""
        assertEquals(200, deliverEvent("/on-user-address-creation-event", "address/user-address/created", address(unknown)))
        assertNull(userAddressesOf(unknown))
        assertEquals(200, deliverEvent("/on-user-address-archived-event", "address/user-address/archived", """{"id":"${UUID.randomUUID()}","userId":"$unknown"}"""))

        val user = UUID.randomUUID()
        assertEquals(200, deliverEvent("/on-user-creation-event", "user/user/created", """{"id":"$user","first_name":"A","last_name":"B"}"""))
        assertEquals(200, deliverEvent("/on-user-address-archived-event", "address/user-address/archived", """{"id":"${UUID.randomUUID()}","userId":"$user"}"""))
        assertEquals(200, deliverEvent("/on-user-address-creation-event", "address/user-address/created", address(user)))
        assertEquals(200, deliverEvent("/on-user-address-creation-event", "address/user-address/created", address(user, "\"Co\"")))
        val (addresses, userAddresses) = userAddressesOf(user)!!
        assertEquals("[]", addresses)
        val pushed = MisarchIntegrationTest.mapper.readTree(userAddresses!!)
        assertEquals(2, pushed.size())
        // jsonb does not keep the key order of the Mongo sub-document (never observable: only `_id` is ever read)
        assertEquals(setOf("_id", "street1", "street2", "city", "postal_code", "country", "company_name", "user_id"),
            pushed.path(0).propertyNames().toSet())
        assertEquals("", pushed.path(0).path("company_name").asString())
        assertEquals("Co", pushed.path(1).path("company_name").asString())
        assertEquals(500, deliverEvent("/on-user-address-archived-event", "address/user-address/archived", """{"id":"${UUID.randomUUID()}","userId":"$user"}"""))
        assertEquals(422, deliverEvent("/on-user-address-creation-event", "address/user-address/created", """{"id":"x"}"""))
    }

    @Test
    fun `event routes reproduce axum status codes`() {
        assertEquals(415, rawPost("/on-user-creation-event", """{"topic":"user/user/created","data":{}}""", "text/plain"))
        assertEquals(400, rawPost("/on-user-creation-event", """{"topic":"""))
        assertEquals(422, rawPost("/on-user-creation-event", """{"data":{"id":"${UUID.randomUUID()}","first_name":"a","last_name":"b"}}"""))
        assertEquals(422, rawPost("/on-discount-validation-succeded", """{"topic":"discount/order/validation-succeeded","data":{}}"""))
    }

    // ------------------------------------------------------------------ GraphQL

    private fun seedInvoice(orderId: UUID, issuedAt: Instant = Instant.parse("2026-10-13T12:00:00.120Z")): UUID {
        val id = UUID.randomUUID()
        runBlocking {
            db.sql(
                "INSERT INTO invoices (id, order_id, issued_at, content, user_address, vendor_address, vat_number) VALUES " +
                    "(:id, :order, :issued, 'content', jsonb_build_object('_id', :ua, 'street1', 's', 'street2', 's', 'city', 'c', " +
                    "'postal_code', 'p', 'country', 'DE', 'company_name', '', 'user_id', :ua), jsonb_build_object('_id', :va, " +
                    "'street1', 's', 'street2', 's', 'city', 'c', 'postal_code', 'p', 'country', 'DE', 'company_name', 'V'), NULL)"
            ).bind("id", id).bind("order", orderId).bind("issued", issuedAt.atOffset(ZoneOffset.UTC))
                .bind("ua", UUID.randomUUID().toString()).bind("va", UUID.randomUUID().toString()).fetch().awaitRowsUpdated()
        }
        return id
    }

    @Test
    fun `invoice query and entities`() {
        val unknown = UUID.randomUUID()
        val missing = graphql("""{ invoice(id: "$unknown") { id } }""")
        assertEquals("misarch_invoice::graphql::model::invoice::Invoice with UUID: `$unknown` not found.", error(missing))
        assertTrue(missing.path("data").isNull)

        val order = UUID.randomUUID()
        val first = seedInvoice(order)
        seedInvoice(order, Instant.parse("2026-10-14T00:00:00Z"))
        val invoice = graphql("""{ invoice(id: "$first") { id orderId issuedAt content userAddress { id } vendorAddress { id } vatNumber } }""")
            .path("data").path("invoice")
        assertEquals(order.toString(), invoice.path("orderId").asString())
        assertEquals("2026-10-13T12:00:00.120+00:00", invoice.path("issuedAt").asString())
        assertTrue(invoice.path("vatNumber").isNull)

        val entities = graphql("""{ _entities(representations: [{__typename: "Order", id: "$order"}, {__typename: "Invoice", id: "$first"}]) { ... on Order { id invoice { id } } ... on Invoice { orderId } } }""")
            .path("data").path("_entities")
        assertEquals(first.toString(), entities.path(0).path("invoice").path("id").asString())
        assertEquals(order.toString(), entities.path(1).path("orderId").asString())

        val noInvoice = UUID.randomUUID()
        val orderMissing = graphql("""{ _entities(representations: [{__typename: "Order", id: "$order"}, {__typename: "Order", id: "$noInvoice"}]) { ... on Order { id } } }""")
        assertEquals("Invoice with order_id UUID: `$noInvoice` not found.", error(orderMissing))
        assertTrue(orderMissing.path("data").isNull)
        assertEquals("Entity not found.", error(graphql("""{ _entities(representations: [{__typename: "UserAddress", id: "$order"}]) { __typename } }""")))
    }

    // ------------------------------------------------------------------ dead path (unit level)

    @Test
    fun `content rendering and the invoice-created event JSON`() {
        val root = MisarchIntegrationTest.mapper.readTree(discountOrder(status = "Placed", payment = """{"cVC":7}"""))
        val order = OrderEventData.fromJson(root.path("order"))
        val invoiceId = UUID.fromString("00000000-0000-4000-8000-000000000001")
        val content = InvoiceService.renderContent(
            "Vendor", "v1", "v2", "vc", "DE", "-", order.userId, "Ada", "Lovelace", "", "s1", "s2", "c", "DE",
            invoiceId, Instant.parse("2026-10-13T12:34:56.789Z"), InvoiceService.renderItems(order), "40",
        )
        val item = order.orderItems.single()
        assertEquals(
            "\n# Invoice\n\n### Company information:\nVendor\nv1, v2\nvc, DE\n\nVAT number: -\n\n### Customer information:\n" +
                "ID: ${order.userId}\nName: Ada, Lovelace\nAddress:\n\ns1, s2\nc, DE\n\n" +
                "### Invoice ID: $invoiceId, issued at: 2026-10-13 12:34:56 \n\n" +
                "Terms and conditions: This invoice is created according the the companies terms and conditions specified on the website.\n\n" +
                "---\n\nPurchased items overview:\n\n| Item UUID | Product variant UUID | count | Compensatable amount |\n" +
                "| --- | --- | --- | --- |\n| ${item.id} | ${item.productVariantId} | 2 | 40 |\n\n\n---\n\nTotal compensatable amount: 40\n",
            content
        )
        val json = InvoiceService.invoiceCreatedJson(order, order.id, Instant.parse("2026-10-13T12:34:56Z"), "c")
        assertEquals(
            """{"order":{"id":"${order.id}","userId":"${order.userId}","createdAt":"2026-10-13T10:00:00.123Z","orderStatus":"Placed",""" +
                """"placedAt":"2026-10-13T10:00:01Z","rejectionReason":null,"orderItems":[{"id":"${item.id}","createdAt":"2026-10-13T10:00:00Z",""" +
                """"productVariantId":"${item.productVariantId}","productVariantVersionId":"${item.productVariantVersionId}",""" +
                """"taxRateVersionId":"${item.taxRateVersionId}","shoppingCartItemId":"${item.shoppingCartItemId}","count":2,""" +
                """"compensatableAmount":40,"shipmentMethodId":"${item.shipmentMethodId}","discountIds":[]}],""" +
                """"shipmentAddressId":"${order.shipmentAddressId}","invoiceAddressId":"${order.invoiceAddressId}",""" +
                """"compensatableOrderAmount":40,"paymentInformationId":"${order.paymentInformationId}","paymentAuthorization":{"cVC":7},""" +
                """"vatNumber":"AB1234"},"invoice":{"orderId":"${order.id}","issuedAt":"2026-10-13T12:34:56Z","content":"c"}}""",
            json
        )
    }

    @Test
    fun `publish goes to component invoice and ignores the HTTP status (defect D4)`() {
        dapr.publishStatus = 404
        runBlocking { invoiceService.publishInvoiceCreated("""{"x":1}""") }
        val published = dapr.published.single()
        assertEquals("invoice", published.pubsubName)
        assertEquals("invoice/created", published.topic)
        assertEquals("""{"x":1}""", published.body)
        assertTrue(published.contentType!!.startsWith("application/json"))
        val unreachable = InvoiceService(store, WebClient.builder().build(), DaprProperties("http://127.0.0.1:1"))
        assertThrows(InvoiceEventException::class.java) { runBlocking { unreachable.publishInvoiceCreated("{}") } }
    }
}
