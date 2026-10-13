package org.misarch.invoice

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.misarch.common.testing.MisarchIntegrationTest
import org.misarch.invoice.event.OrderEventData
import org.misarch.invoice.persistence.InvoiceStore
import org.misarch.invoice.service.InvoiceEventException
import org.misarch.invoice.service.InvoiceService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitOneOrNull
import org.springframework.r2dbc.core.awaitRowsUpdated
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

    // ------------------------------------------------------------------ Dapr

    private val vendorTopic = "address/vendor-address/created"
    private val validationTopic = "discount/order/validation-succeeded"

    /** A user (camelCase user event), its invoice address (real address payload) and a complete vendor address. */
    private fun seedReplicas(user: UUID = UUID.randomUUID(), address: UUID = UUID.randomUUID(), withUser: Boolean = true): Pair<UUID, UUID> {
        if (withUser) {
            assertEquals(200, deliverEvent("/on-id-creation-event", "user/user/created",
                """{"id":"$user","username":"ada","firstName":"Ada","lastName":"Lovelace","dateJoined":"2026-10-13T12:00:00Z"}"""))
        }
        assertEquals(200, deliverEvent("/on-user-address-creation-event", "address/user-address/created",
            """{"id":"$address","name":{"firstName":"Grace","lastName":"Hopper"},"street1":"s1","street2":"s2","city":"Leipzig","postalCode":"04109","country":"Germany","companyName":null,"userId":"$user"}"""))
        assertEquals(200, deliverEvent("/on-vendor-address-creation-event", vendorTopic,
            """{"id":"${UUID.randomUUID()}","name":{"firstName":"Shop","lastName":"Owner"},"street1":"Universitaetsstrasse","street2":"38","city":"Stuttgart","postalCode":"70569","country":"Germany","companyName":"MiSArch Shop GmbH"}"""))
        return user to address
    }

    private fun invoicesOf(orderId: String): Long = runBlocking {
        db.sql("SELECT COUNT(*) AS c FROM invoices WHERE order_id = :o").bind("o", UUID.fromString(orderId))
            .map { row -> row.get("c", java.lang.Long::class.java)!!.toLong() }.awaitOneOrNull()!!
    }

    @Test
    fun `dapr subscribe equals the original, the subscribed user route is served`() {
        val body = webTestClient.get().uri("/dapr/subscribe").exchange().expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!
        val reference = File(System.getProperty("misarch.reference.dir"), "dapr-subscribe/invoice.json").readText()
        assertEquals(reference.trim(), body.trim())
        // FIXED (D1): the subscribed route exists and accepts the user service's camelCase payload (was 404, dropped)
        val id = UUID.randomUUID()
        assertEquals(200, deliverEvent("/on-id-creation-event", "user/user/created", """{"id":"$id","firstName":"a","lastName":"b"}"""))
        // idempotent (a duplicate was a 500 forever); the original's snake_case is still accepted
        assertEquals(200, deliverEvent("/on-id-creation-event", "user/user/created", """{"id":"$id","first_name":"a","last_name":"b"}"""))
        webTestClient.get().uri("/health").exchange().expectStatus().isOk.expectBody().isEmpty()
    }

    @Test
    fun `the real discount payload creates one invoice and publishes invoice created (D1-D6 fixed)`() {
        val (user, address) = seedReplicas()
        val payload = discountOrder(address, user)
        val orderId = MisarchIntegrationTest.mapper.readTree(payload).path("order").path("id").asString()
        dapr.reset()
        assertEquals(200, deliverEvent("/on-discount-validation-succeded", validationTopic, payload))
        assertEquals(1, invoicesOf(orderId))
        val event = dapr.awaitPublished("invoice/invoice/created").single()
        assertEquals("pubsub", event.pubsubName)
        assertEquals(orderId, event.json.path("invoice").path("orderId").asString())
        assertEquals("PLACED", event.json.path("order").path("orderStatus").asString())
        assertEquals("""{"cvc":123}""", event.json.path("order").path("paymentAuthorization").toString())
        val content = event.json.path("invoice").path("content").asString()
        assertTrue(content.contains("MiSArch Shop GmbH\nUniversitaetsstrasse, 38\nStuttgart, Germany"), content)
        assertTrue(content.contains("Name: Ada, Lovelace\nAddress:\n\ns1, s2\nLeipzig, Germany"), content)
        assertTrue(content.contains("VAT number: AB1234") && content.endsWith("Total compensatable amount: 40\n"), content)

        // federation: Order.invoice resolves
        val entity = graphql("""{ _entities(representations: [{__typename: "Order", id: "$orderId"}]) { ... on Order { invoice { orderId content userAddress { id } } } } }""")
        assertEquals(content, entity.path("data").path("_entities").path(0).path("invoice").path("content").asString())
        assertEquals(address.toString(), entity.path("data").path("_entities").path(0).path("invoice").path("userAddress").path("id").asString())

        // a redelivery is acknowledged without a second invoice or event
        dapr.reset()
        assertEquals(200, deliverEvent("/on-discount-validation-succeded", validationTopic, payload))
        assertEquals(1, invoicesOf(orderId))
        assertTrue(dapr.published.isEmpty())
        // wrong topic
        assertEquals(500, deliverEvent("/on-discount-validation-succeded", "discount/order/validation-failed", payload))
    }

    @Test
    fun `missing replicas answer 500 until they arrive, the addressee's name is the fallback for an unknown user`() {
        val user = UUID.randomUUID()
        val address = UUID.randomUUID()
        val payload = discountOrder(address, user)
        val orderId = MisarchIntegrationTest.mapper.readTree(payload).path("order").path("id").asString()
        assertEquals(500, deliverEvent("/on-discount-validation-succeded", validationTopic, payload))
        assertEquals(0, invoicesOf(orderId))
        seedReplicas(user, address, withUser = false)
        dapr.reset()
        assertEquals(200, deliverEvent("/on-discount-validation-succeded", validationTopic, payload))
        val content = dapr.awaitPublished("invoice/invoice/created").single().json.path("invoice").path("content").asString()
        assertTrue(content.contains("Name: Grace, Hopper"), content)
    }

    @Test
    fun `the former serde forms are still accepted, malformed orders are 422`() {
        val (user, address) = seedReplicas()
        assertEquals(200, deliverEvent("/on-discount-validation-succeded", validationTopic,
            discountOrder(address, user, status = "Placed", payment = """{"cVC":123}""")))
        assertEquals(422, deliverEvent("/on-discount-validation-succeded", validationTopic, discountOrder(address, user, status = "SHIPPED")))
        assertEquals(422, deliverEvent("/on-discount-validation-succeded", validationTopic, discountOrder(address, user, payment = """{"x":1}""")))
    }

    @Test
    fun `a failed publish rolls the invoice back and the redelivery creates it`() {
        val (user, address) = seedReplicas()
        val payload = discountOrder(address, user)
        val orderId = MisarchIntegrationTest.mapper.readTree(payload).path("order").path("id").asString()
        dapr.publishStatus = 500
        try {
            assertEquals(500, deliverEvent("/on-discount-validation-succeded", validationTopic, payload))
        } finally {
            dapr.publishStatus = 204
        }
        assertEquals(0, invoicesOf(orderId))
        assertEquals(200, deliverEvent("/on-discount-validation-succeded", validationTopic, payload))
        assertEquals(1, invoicesOf(orderId))
    }

    @Test
    fun `vendor address events store all fields, camelCase and snake_case, idempotently (D5 fixed)`() {
        val id = UUID.randomUUID()
        val camel = """{"id":"$id","name":null,"street1":"a","street2":"b","city":"c","postalCode":"p","country":"DE","companyName":"V"}"""
        assertEquals(200, deliverEvent("/on-vendor-address-creation-event", vendorTopic, camel))
        assertEquals(200, deliverEvent("/on-vendor-address-creation-event", vendorTopic, camel))
        val snake = """{"id":"$id","street1":"a2","street2":"b","city":"c","postal_code":"p","country":"DE","company_name":"V2"}"""
        assertEquals(200, deliverEvent("/on-vendor-address-creation-event", vendorTopic, snake))
        val row = runBlocking {
            db.sql("SELECT street1, postal_code, company_name FROM vendor_address WHERE id = :id").bind("id", id)
                .map { r -> listOf(r.get("street1", String::class.java), r.get("postal_code", String::class.java), r.get("company_name", String::class.java)) }
                .awaitOneOrNull()
        }
        assertEquals(listOf("a2", "p", "V2"), row)
        assertEquals(500, deliverEvent("/on-vendor-address-creation-event", "address/user-address/created", snake))
    }

    @Test
    fun `user address events are stored independently of the user and archived (D3 fixed)`() {
        val user = UUID.randomUUID()
        val id = UUID.randomUUID()
        val created = """{"id":"$id","name":{"firstName":"A","lastName":"B"},"street1":"s1","street2":"s2","city":"c","postalCode":"p","country":"DE","companyName":"Co","userId":"$user"}"""
        assertEquals(200, deliverEvent("/on-user-address-creation-event", "address/user-address/created", created))
        assertEquals(200, deliverEvent("/on-user-address-creation-event", "address/user-address/created", created))
        val stored = runBlocking { store.findUserAddress(id) }!!
        assertEquals(listOf("Co", "A", "B", user.toString()), listOf(stored.companyName, stored.firstName, stored.lastName, stored.userId.toString()))
        // archive: no failure (the original's $pull failed with 500 for users with addresses)
        assertEquals(200, deliverEvent("/on-user-address-archived-event", "address/user-address/archived", """{"id":"$id","userId":"$user"}"""))
        assertEquals(200, deliverEvent("/on-user-address-archived-event", "address/user-address/archived", """{"id":"${UUID.randomUUID()}","userId":"$user"}"""))
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

    // ------------------------------------------------------------------ rendering (unit level)

    @Test
    fun `content rendering and the invoice-created event JSON`() {
        val root = MisarchIntegrationTest.mapper.readTree(discountOrder(payment = """{"cvc":7}"""))
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
            """{"order":{"id":"${order.id}","userId":"${order.userId}","createdAt":"2026-10-13T10:00:00.123Z","orderStatus":"PLACED",""" +
                """"placedAt":"2026-10-13T10:00:01Z","rejectionReason":null,"orderItems":[{"id":"${item.id}","createdAt":"2026-10-13T10:00:00Z",""" +
                """"productVariantId":"${item.productVariantId}","productVariantVersionId":"${item.productVariantVersionId}",""" +
                """"taxRateVersionId":"${item.taxRateVersionId}","shoppingCartItemId":"${item.shoppingCartItemId}","count":2,""" +
                """"compensatableAmount":40,"shipmentMethodId":"${item.shipmentMethodId}","discountIds":[]}],""" +
                """"shipmentAddressId":"${order.shipmentAddressId}","invoiceAddressId":"${order.invoiceAddressId}",""" +
                """"compensatableOrderAmount":40,"paymentInformationId":"${order.paymentInformationId}","paymentAuthorization":{"cvc":7},""" +
                """"vatNumber":"AB1234"},"invoice":{"orderId":"${order.id}","issuedAt":"2026-10-13T12:34:56Z","content":"c"}}""",
            json
        )
    }

    @Test
    fun `publish goes to component pubsub, topic invoice-invoice-created, and fails on an error answer (D4 fixed)`() {
        dapr.reset()
        runBlocking { invoiceService.publishInvoiceCreated("""{"x":1}""") }
        val published = dapr.published.single()
        assertEquals("pubsub", published.pubsubName)
        assertEquals("invoice/invoice/created", published.topic)
        assertEquals("""{"x":1}""", published.body)
        assertTrue(published.contentType!!.startsWith("application/json"))
        dapr.publishStatus = 404
        try {
            assertThrows(InvoiceEventException::class.java) { runBlocking { invoiceService.publishInvoiceCreated("{}") } }
        } finally {
            dapr.publishStatus = 204
        }
    }
}
