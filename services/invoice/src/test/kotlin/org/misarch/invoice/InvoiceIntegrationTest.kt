package org.misarch.invoice

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.misarch.common.testing.MisarchIntegrationTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.json.JsonCompareMode
import tools.jackson.databind.JsonNode
import java.io.File
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Behaviour of the invoice service against a real Postgres (Testcontainers) and a fake Dapr sidecar: the replica
 * events, the invoice of a validated order with the published event, the query and the federation entities.
 */
class InvoiceIntegrationTest : MisarchIntegrationTest() {

    override val graphqlPath: String = "/"

    @Autowired
    lateinit var databaseClient: DatabaseClient

    private val referenceDir = File(System.getProperty("misarch.reference.dir"))

    private fun sql(statement: String): List<Map<String, Any?>> =
        databaseClient.sql(statement).fetch().all().collectList().block()!!

    private fun deliverOk(topic: String, data: String) {
        assertThat(deliverEvent("/subscription/$topic", topic, data)).isEqualTo(200)
    }

    private fun validationSucceeded(payload: String) =
        deliverEvent("/subscription/discount/order/validation-succeeded", "discount/order/validation-succeeded", payload)

    /** The payload discount publishes (Jackson, `null`s omitted). */
    private fun validatedOrder(invoiceAddress: UUID, user: UUID, payment: String = ""","paymentAuthorization":{"cvc":123}""") = """
        {"order":{"id":"${UUID.randomUUID()}","userId":"$user","createdAt":"2026-10-13T10:00:00.123Z",
         "orderStatus":"PLACED","placedAt":"2026-10-13T10:00:01Z",
         "orderItems":[{"id":"${UUID.randomUUID()}","createdAt":"2026-10-13T10:00:00.120Z","productVariantVersionId":"${UUID.randomUUID()}",
           "productVariantId":"${UUID.randomUUID()}","taxRateVersionId":"${UUID.randomUUID()}","shoppingCartItemId":"${UUID.randomUUID()}",
           "count":2,"compensatableAmount":40,"shipmentMethodId":"${UUID.randomUUID()}","discountIds":[]}],
         "shipmentAddressId":"$invoiceAddress","invoiceAddressId":"$invoiceAddress","compensatableOrderAmount":40,
         "paymentInformationId":"${UUID.randomUUID()}"$payment,"vatNumber":"AB1234"}}
    """.trimIndent()

    private fun orderId(payload: String): UUID = UUID.fromString(mapper.readTree(payload)["order"]["id"].asString())

    /** A user, its invoice address and a complete vendor address (the payloads of the user and address services). */
    private fun seedReplicas(user: UUID = UUID.randomUUID(), address: UUID = UUID.randomUUID(), withUser: Boolean = true): Pair<UUID, UUID> {
        if (withUser) {
            deliverOk("user/user/created", """{"id":"$user","username":"ada","firstName":"Ada","lastName":"Lovelace","dateJoined":"2026-10-13T12:00:00Z"}""")
        }
        deliverOk(
            "address/user-address/created",
            """{"id":"$address","name":{"firstName":"Grace","lastName":"Hopper"},"street1":"s1","street2":"s2","city":"Leipzig","postalCode":"04109","country":"Germany","companyName":null,"userId":"$user"}"""
        )
        deliverOk(
            "address/vendor-address/created",
            """{"id":"${UUID.randomUUID()}","name":{"firstName":"Shop","lastName":"Owner"},"street1":"Universitaetsstrasse","street2":"38","city":"Stuttgart","postalCode":"70569","country":"Germany","companyName":"MiSArch Shop GmbH"}"""
        )
        return user to address
    }

    private fun invoicesOf(orderId: UUID) = sql("SELECT * FROM InvoiceEntity WHERE orderId = '$orderId'")

    @Test
    fun `dapr subscribe lists every consumed topic on its own route`() {
        val body = webTestClient.get().uri("/dapr/subscribe").exchange().expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!
        val subscriptions = mapper.readTree(body).toList().map { "${it["pubsubName"].asString()} ${it["topic"].asString()} ${it["route"].asString()}" }
        val topics = mapper.readTree(File(referenceDir, "dapr-subscribe/invoice.json")).toList().map { it["topic"].asString() }
        assertThat(subscriptions).containsExactlyInAnyOrderElementsOf(topics.map { "pubsub $it /subscription/$it" })
    }

    @Test
    fun `health endpoint is UP`() {
        webTestClient.get().uri("/health").exchange()
            .expectStatus().isOk
            .expectBody().json("""{"status":"UP"}""", JsonCompareMode.STRICT)
    }

    @Test
    fun `the CloudEvents captured from the running services are accepted`() {
        listOf("user_user_created", "address_user-address_created", "address_vendor-address_created").forEach { file ->
            val cloudEvent = File(referenceDir, "events/$file.json").readText()
            val topic = mapper.readTree(cloudEvent)["topic"].asString()
            val status = webTestClient.post().uri("/subscription/$topic")
                .header("Content-Type", "application/cloudevents+json").bodyValue(cloudEvent)
                .exchange().returnResult(String::class.java).status.value()
            assertThat(status).describedAs(file).isEqualTo(200)
        }
        val user = mapper.readTree(File(referenceDir, "events/user_user_created.json"))["data"]
        assertThat(sql("SELECT firstName, lastName FROM UserEntity WHERE id = '${user["id"].asString()}'").single())
            .isEqualTo(mapOf("firstname" to user["firstName"].asString(), "lastname" to user["lastName"].asString()))
    }

    @Test
    fun `a validated order gets one invoice, published with the order`() {
        val (user, address) = seedReplicas()
        val payload = validatedOrder(address, user)
        val orderId = orderId(payload)
        dapr.reset()
        assertThat(validationSucceeded(payload)).isEqualTo(200)
        val stored = invoicesOf(orderId).single()
        assertThat(stored["useraddressid"]).isEqualTo(address)
        assertThat(stored["vatnumber"]).isEqualTo("AB1234")
        val invoiceId = stored["id"] as UUID
        val issuedAt = (stored["issuedat"] as OffsetDateTime).withOffsetSameInstant(ZoneOffset.UTC)
        val order = mapper.readTree(payload)["order"]
        val item = order["orderItems"][0]
        val content = "\n# Invoice\n\n### Company information:\nMiSArch Shop GmbH\nUniversitaetsstrasse, 38\nStuttgart, Germany\n\n" +
            "VAT number: AB1234\n\n### Customer information:\nID: $user\nName: Ada, Lovelace\nAddress:\n\ns1, s2\nLeipzig, Germany\n\n" +
            "### Invoice ID: $invoiceId, issued at: ${issuedAt.format(DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss"))} \n\n" +
            "Terms and conditions: This invoice is created according the the companies terms and conditions specified on the website.\n\n" +
            "---\n\nPurchased items overview:\n\n| Item UUID | Product variant UUID | count | Compensatable amount |\n" +
            "| --- | --- | --- | --- |\n| ${item["id"].asString()} | ${item["productVariantId"].asString()} | 2 | 40 |\n\n\n---\n\n" +
            "Total compensatable amount: 40\n"
        assertThat(stored["content"]).isEqualTo(content)

        val event = dapr.awaitPublished("invoice/invoice/created").single()
        assertThat(event.pubsubName).isEqualTo("pubsub")
        fun s(node: JsonNode, name: String) = node[name].asString()
        val expectedOrder = """{"id":"$orderId","userId":"$user","createdAt":"2026-10-13T10:00:00.123Z","orderStatus":"PLACED",""" +
            """"placedAt":"2026-10-13T10:00:01Z","rejectionReason":null,"orderItems":[{"id":"${s(item, "id")}",""" +
            """"createdAt":"2026-10-13T10:00:00.12Z","productVariantId":"${s(item, "productVariantId")}",""" +
            """"productVariantVersionId":"${s(item, "productVariantVersionId")}","taxRateVersionId":"${s(item, "taxRateVersionId")}",""" +
            """"shoppingCartItemId":"${s(item, "shoppingCartItemId")}","count":2,"compensatableAmount":40,""" +
            """"shipmentMethodId":"${s(item, "shipmentMethodId")}","discountIds":[]}],"shipmentAddressId":"$address",""" +
            """"invoiceAddressId":"$address","compensatableOrderAmount":40,"paymentInformationId":"${s(order, "paymentInformationId")}",""" +
            """"paymentAuthorization":{"cvc":123},"vatNumber":"AB1234"}"""
        assertThat(event.body).isEqualTo(
            """{"order":$expectedOrder,"invoice":{"orderId":"$orderId","issuedAt":"${issuedAt.toInstant()}","content":${mapper.writeValueAsString(content)}}}"""
        )

        // a redelivery is acknowledged without a second invoice or event
        dapr.reset()
        assertThat(validationSucceeded(payload)).isEqualTo(200)
        assertThat(invoicesOf(orderId)).hasSize(1)
        assertThat(dapr.published).isEmpty()
    }

    @Test
    fun `missing replicas fail the event until they arrive, the addressee's name replaces an unknown user`() {
        val user = UUID.randomUUID()
        val address = UUID.randomUUID()
        val payload = validatedOrder(address, user, payment = "")
        assertThat(validationSucceeded(payload)).isEqualTo(500)
        assertThat(invoicesOf(orderId(payload))).isEmpty()
        seedReplicas(user, address, withUser = false)
        dapr.reset()
        assertThat(validationSucceeded(payload)).isEqualTo(200)
        val event = dapr.awaitPublished("invoice/invoice/created").single().json
        assertThat(event["invoice"]["content"].asString()).contains("Name: Grace, Hopper")
        assertThat(event["order"]["paymentAuthorization"].isNull).isTrue()
        assertThat(validationSucceeded("""{"order":{"id":"x"}}""")).isEqualTo(400)
    }

    @Test
    fun `a failed publish rolls the invoice back, the redelivery creates it`() {
        val (user, address) = seedReplicas()
        val payload = validatedOrder(address, user)
        dapr.publishStatus = 500
        assertThat(validationSucceeded(payload)).isEqualTo(500)
        assertThat(invoicesOf(orderId(payload))).isEmpty()
        dapr.publishStatus = 204
        assertThat(validationSucceeded(payload)).isEqualTo(200)
        assertThat(invoicesOf(orderId(payload))).hasSize(1)
    }

    @Test
    fun `vendor address events store all fields, a redelivery updates them`() {
        val id = UUID.randomUUID()
        deliverOk("address/vendor-address/created", """{"id":"$id","name":null,"street1":"a","street2":"b","city":"c","postalCode":"p","country":"DE","companyName":"V"}""")
        deliverOk("address/vendor-address/created", """{"id":"$id","street1":"a2","street2":"b","city":"c","postalCode":"p","country":"DE","companyName":null}""")
        assertThat(sql("SELECT street1, postalCode, companyName FROM VendorAddressEntity WHERE id = '$id'").single())
            .isEqualTo(mapOf("street1" to "a2", "postalcode" to "p", "companyname" to null))
    }

    @Test
    fun `user address events are stored independently of the user and archived`() {
        val user = UUID.randomUUID()
        val id = UUID.randomUUID()
        val created = """{"id":"$id","name":{"firstName":"A","lastName":"B"},"street1":"s1","street2":"s2","city":"c","postalCode":"p","country":"DE","companyName":"Co","userId":"$user"}"""
        deliverOk("address/user-address/created", created)
        deliverOk("address/user-address/created", created)
        fun stored() = sql("SELECT companyName, firstName, lastName, userId, isArchived FROM UserAddressEntity WHERE id = '$id'").single()
        assertThat(stored()).isEqualTo(mapOf("companyname" to "Co", "firstname" to "A", "lastname" to "B", "userid" to user, "isarchived" to false))
        deliverOk("address/user-address/archived", """{"id":"$id","userId":"$user"}""")
        assertThat(stored()["isarchived"]).isEqualTo(true)
        deliverOk("address/user-address/archived", """{"id":"${UUID.randomUUID()}","userId":"$user"}""")
    }

    @Test
    fun `invoice query and federation entities`() {
        val (user, address) = seedReplicas()
        val payload = validatedOrder(address, user)
        val orderId = orderId(payload)
        assertThat(validationSucceeded(payload)).isEqualTo(200)
        val stored = invoicesOf(orderId).single()
        val invoiceId = stored["id"] as UUID

        val invoice = graphql("""{ invoice(id: "$invoiceId") { id orderId issuedAt content userAddress { id } vendorAddress { id } vatNumber } }""")
        assertThat(invoice["errors"]).isNull()
        val node = invoice["data"]["invoice"]
        assertThat(node["orderId"].asString()).isEqualTo(orderId.toString())
        assertThat(OffsetDateTime.parse(node["issuedAt"].asString()).toInstant()).isEqualTo((stored["issuedat"] as OffsetDateTime).toInstant())
        assertThat(node["content"].asString()).isEqualTo(stored["content"])
        assertThat(node["userAddress"]["id"].asString()).isEqualTo(address.toString())
        assertThat(node["vendorAddress"]["id"].asString()).isEqualTo(stored["vendoraddressid"].toString())
        assertThat(node["vatNumber"].asString()).isEqualTo("AB1234")
        assertThat(graphql("""{ invoice(id: "${UUID.randomUUID()}") { id } }""")["errors"][0]["message"].asString()).endsWith(": null")

        val entities = graphql(
            """{ _entities(representations: [{__typename: "Order", id: "$orderId"}, {__typename: "Invoice", id: "$invoiceId"}]) {
                 ... on Order { id invoice { id } } ... on Invoice { orderId } } }"""
        )
        assertThat(entities["errors"]).isNull()
        assertThat(entities["data"]["_entities"][0]["invoice"]["id"].asString()).isEqualTo(invoiceId.toString())
        assertThat(entities["data"]["_entities"][1]["orderId"].asString()).isEqualTo(orderId.toString())
        // an order without an invoice
        val noInvoice = graphql("""{ _entities(representations: [{__typename: "Order", id: "${UUID.randomUUID()}"}]) { ... on Order { id invoice { id } } } }""")
        assertThat(noInvoice["errors"][0]["message"].asString()).endsWith(": null")
    }

}
