package org.misarch.shipment

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.misarch.common.testing.MisarchIntegrationTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.json.JsonCompareMode
import tools.jackson.databind.JsonNode
import java.io.File
import java.util.UUID

/**
 * Behaviour of the shipment service against a real Postgres (Testcontainers), a fake Dapr sidecar and a fake
 * shipment provider. Expected values (error texts, JSON payloads, fee results) were observed on the original
 * shipment service (docs/specs/shipment.md §9a).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ShipmentIntegrationTest : MisarchIntegrationTest() {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun providerProperties(registry: DynamicPropertyRegistry) {
            registry.add("misarch.shipment.provider.endpoint") { FakeShipmentProvider.endpoint }
        }
    }

    @Autowired
    lateinit var db: DatabaseClient

    private val provider = FakeShipmentProvider
    private val employeeId = UUID.randomUUID()
    private val employee = authorizedUserHeader(employeeId, "employee")
    private val buyerId = UUID.randomUUID()
    private val buyer = authorizedUserHeader(buyerId, "buyer")

    @BeforeEach
    fun resetProvider() {
        provider.reset()
        setEcs("""{"providerRetries":3,"providerRetryDelay":500}""")
    }

    // ---------------------------------------------------------------- helpers

    private fun setEcs(json: String): Int = webTestClient.post().uri("/ecs/variables")
        .header("Content-Type", "application/json").bodyValue(json).exchange()
        .returnResult(String::class.java).status.value()

    private fun vendorAddress(id: UUID = UUID.randomUUID(), companyName: String? = "ACME"): UUID {
        val company = companyName?.let { "\"$it\"" } ?: "null"
        val status = deliverEvent(
            "/subscription/address/vendor-address/created", "address/vendor-address/created",
            """{"id":"$id","name":null,"street1":"s1","street2":"s2","city":"c","postalCode":"p","country":"de","companyName":$company}"""
        )
        assertThat(status).isEqualTo(200)
        return id
    }

    private fun userAddress(id: UUID = UUID.randomUUID(), userId: UUID = buyerId): UUID {
        val status = deliverEvent(
            "/subscription/address/user-address/created", "address/user-address/created",
            """{"id":"$id","name":{"firstName":"a","lastName":"b"},"street1":"u1","street2":"u2","city":"uc","postalCode":"up","country":"de","companyName":null,"userId":"$userId"}"""
        )
        assertThat(status).isEqualTo(200)
        return id
    }

    private fun pvv(weight: Double, id: UUID = UUID.randomUUID()): UUID {
        val status = deliverEvent(
            "/subscription/catalog/product-variant-version/created", "catalog/product-variant-version/created",
            """{"id":"$id","name":"n","description":"d","version":1,"retailPrice":100,"createdAt":"2024-01-01T00:00:00Z","canBeReturnedForDays":null,"productVariantId":"$id","taxRateId":"$id","weight":$weight,"mediaIds":[]}"""
        )
        assertThat(status).isEqualTo(200)
        return id
    }

    private fun createMethod(name: String, ref: String, baseFees: Int, feesPerItem: Int, feesPerKg: Int): UUID {
        val result = graphql(
            """mutation { createShipmentMethod(input: {name: "$name", description: "d", externalReference: "$ref", baseFees: $baseFees, feesPerItem: $feesPerItem, feesPerKg: $feesPerKg}) { id } }""",
            user = employee
        )
        assertThat(result["errors"]).isNull()
        return UUID.fromString(result["data"]["createShipmentMethod"]["id"].asString())
    }

    private data class Item(val id: UUID, val pvv: UUID, val count: Long, val method: UUID)

    private fun orderJson(orderId: UUID, addressId: UUID, items: List<Item>): String {
        val itemJson = items.joinToString(",") {
            """{"id":"${it.id}","createdAt":"2024-01-01T00:00:00Z","productVariantId":"${it.pvv}","productVariantVersionId":"${it.pvv}","taxRateVersionId":"${it.pvv}","shoppingCartItemId":"${it.pvv}","count":${it.count},"compensatableAmount":10,"shipmentMethodId":"${it.method}","discountIds":[]}"""
        }
        return """{"order":{"id":"$orderId","userId":"$buyerId","createdAt":"2024-01-01T00:00:00Z","orderStatus":"PLACED","placedAt":"2024-01-01T00:00:00Z","rejectionReason":null,"orderItems":[$itemJson],"shipmentAddressId":"$addressId","invoiceAddressId":"$addressId","compensatableOrderAmount":30,"paymentInformationId":"$addressId","paymentAuthorization":null,"vatNumber":null}}"""
    }

    private fun paymentEnabled(json: String): Int =
        deliverEvent("/subscription/payment/payment/payment-enabled", "payment/payment/payment-enabled", json)

    private fun returnCreated(json: String): Int =
        deliverEvent("/subscription/return/return/created", "return/return/created", json)

    private fun errorMessage(result: JsonNode): String = result["errors"][0]["message"].asString()

    private fun count(sql: String): Int = db.sql(sql).map { row -> (row.get(0) as Number).toInt() }.one().block()!!

    private fun shipmentIdsOf(orderId: UUID): List<String> =
        db.sql("SELECT id FROM ShipmentEntity WHERE orderId = :o ORDER BY id").bind("o", orderId)
            .map { row -> row.get(0).toString() }.all().collectList().block()!!

    // ---------------------------------------------------------------- tests

    @Test
    @Order(1)
    fun `return shipment without any vendor address fails with 500 and publishes nothing`() {
        assertThat(count("SELECT COUNT(*) FROM AddressEntity WHERE userId IS NULL")).isEqualTo(0)
        val status = returnCreated("""{"id":"${UUID.randomUUID()}","orderId":"${UUID.randomUUID()}","orderItemIds":["${UUID.randomUUID()}"]}""")
        assertThat(status).isEqualTo(500)
        assertThat(dapr.published).isEmpty()
    }

    @Test
    fun `Flyway applied the original migrations with the original checksums`() {
        val checksums = db.sql("SELECT version, checksum FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank")
            .map { row -> row.get("version").toString() to (row.get("checksum") as Number).toInt() }.all().collectList().block()!!
        // recorded by the original service's Flyway 9.22.3 (oracle run)
        assertThat(checksums).containsExactly(
            "1.1" to 1345248039, "1.2" to -327098381, "1.3" to -881856978, "1.4" to -889060718, "1.5" to 821113280
        )
    }

    @Test
    fun `dapr subscribe, health and experiment-config endpoints`() {
        val body = webTestClient.get().uri("/dapr/subscribe").exchange().expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!
        val reference = File(System.getProperty("misarch.reference.dir"), "dapr-subscribe/shipment.json").readText()
        assertThat(mapper.readTree(body).toSet()).isEqualTo(mapper.readTree(reference).toSet())
        webTestClient.get().uri("/health").exchange().expectStatus().isOk
            .expectBody().json("""{"status":"UP"}""", JsonCompareMode.STRICT)
        webTestClient.get().uri("/ecs/defined-variables").exchange().expectStatus().isOk
            .expectBody(String::class.java).isEqualTo(
                """{"providerRetries":{"type":{"${'$'}schema":"http://json-schema.org/draft-07/schema#","type":"integer","minimum":0},"defaultValue":3},"providerRetryDelay":{"type":{"${'$'}schema":"http://json-schema.org/draft-07/schema#","type":"integer","minimum":0},"defaultValue":500}}"""
            )
        assertThat(setEcs("""{"providerRetries":1,"providerRetryDelay":10}""")).isEqualTo(200)
        assertThat(setEcs("""{"providerRetries":1.5}""")).isEqualTo(500)
        assertThat(setEcs("""{"x":1}""")).isEqualTo(500)
    }

    @Test
    fun `address and product variant version events are stored, duplicates fail, invalid payloads are rejected`() {
        val vendor = vendorAddress(companyName = null)
        val user = userAddress()
        val p = pvv(0.5)
        assertThat(count("SELECT COUNT(*) FROM AddressEntity WHERE id = '$vendor' AND userId IS NULL AND companyName IS NULL")).isEqualTo(1)
        assertThat(count("SELECT COUNT(*) FROM AddressEntity WHERE id = '$user' AND userId = '$buyerId'")).isEqualTo(1)
        assertThat(count("SELECT COUNT(*) FROM ProductVariantVersionEntity WHERE id = '$p' AND weight = 0.5")).isEqualTo(1)

        // non-idempotent inserts, like the original
        assertThat(deliverEvent("/subscription/address/user-address/created", "address/user-address/created",
            """{"id":"$user","street1":"u1","street2":"u2","city":"uc","postalCode":"up","country":"de","userId":"$buyerId"}""")).isEqualTo(500)
        assertThat(deliverEvent("/subscription/catalog/product-variant-version/created", "catalog/product-variant-version/created",
            """{"id":"$p","weight":1.0}""")).isEqualTo(500)
        // missing required field
        assertThat(deliverEvent("/subscription/address/user-address/created", "address/user-address/created",
            """{"id":"${UUID.randomUUID()}","street1":"u1","city":"uc","postalCode":"up","country":"de","userId":"$buyerId"}""")).isEqualTo(400)
        assertThat(paymentEnabled("""{"order":{"id":"${UUID.randomUUID()}","placedAt":null}}""")).isEqualTo(400)
    }

    @Test
    fun `shipment method mutations need an employee and publish events`() {
        val mutation = """mutation { createShipmentMethod(input: {name: "n", description: "d", externalReference: "r", baseFees: 1, feesPerItem: 1, feesPerKg: 1}) { id } }"""
        assertThat(errorMessage(graphql(mutation))).isEqualTo("Exception while fetching data (/createShipmentMethod) : Unauthorized access")
        assertThat(errorMessage(graphql(mutation, user = buyer)))
            .isEqualTo("Exception while fetching data (/createShipmentMethod) : Unauthorized access: $buyerId is not an employee or admin")
        assertThat(dapr.published).isEmpty()

        val created = graphql(
            """mutation { createShipmentMethod(input: {name: "std", description: "desc", externalReference: "ref-std", baseFees: 500, feesPerItem: 20, feesPerKg: 150}) { id name description baseFees feesPerItem feesPerKg archivedAt isArchived externalReference } }""",
            user = employee
        )["data"]["createShipmentMethod"]
        val id = created["id"].asString()
        assertThat(created["isArchived"].asBoolean()).isFalse()
        assertThat(created["archivedAt"].isNull).isTrue()
        assertThat(created["externalReference"].asString()).isEqualTo("ref-std")
        assertThat(created.toString()).contains("\"baseFees\":500")
        assertThat(dapr.published("shipment/shipment-method/created").single().body).isEqualTo(
            """{"id":"$id","name":"std","description":"desc","externalReference":"ref-std","baseFees":500,"feesPerItem":20,"feesPerKg":150}"""
        )

        val archived = graphql("""mutation { archiveShipmentMethod(input: {id: "$id"}) { id isArchived archivedAt } }""", user = employee)
        assertThat(archived["data"]["archiveShipmentMethod"]["isArchived"].asBoolean()).isTrue()
        assertThat(archived["data"]["archiveShipmentMethod"]["archivedAt"].asString())
            .matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}(Z|[+-]\\d\\d:\\d\\d)")
        // re-archiving is allowed and publishes again
        graphql("""mutation { archiveShipmentMethod(input: {id: "$id"}) { id } }""", user = employee)
        assertThat(dapr.published("shipment/shipment-method/archived").map { it.body }).containsExactly("""{"id":"$id"}""", """{"id":"$id"}""")

        val unknown = graphql("""mutation { archiveShipmentMethod(input: {id: "${UUID.randomUUID()}"}) { id } }""", user = employee)
        assertThat(errorMessage(unknown)).isEqualTo("Exception while fetching data (/archiveShipmentMethod) : null")
    }

    @Test
    fun `a failing publish rolls the shipment method back`() {
        dapr.publishStatus = 500
        val result = graphql(
            """mutation { createShipmentMethod(input: {name: "rolled-back", description: "d", externalReference: "r", baseFees: 1, feesPerItem: 1, feesPerKg: 1}) { id } }""",
            user = employee
        )
        dapr.publishStatus = 204
        assertThat(result["errors"]).isNotNull()
        assertThat(count("SELECT COUNT(*) FROM ShipmentMethodEntity WHERE name = 'rolled-back'")).isEqualTo(0)
    }

    @Test
    fun `shipmentMethods connection, externalReference authorization and lookups`() {
        val a = createMethod("conn-a", "ra", 1, 1, 1)
        val b = createMethod("conn-b", "rb", 1, 1, 1)
        graphql("""mutation { archiveShipmentMethod(input: {id: "$b"}) { id } }""", user = employee)

        val all = graphql("{ shipmentMethods { totalCount hasNextPage nodes { id isArchived } } }")["data"]["shipmentMethods"]
        val total = all["totalCount"].asInt()
        assertThat(all["hasNextPage"].asBoolean()).isFalse()
        val ids = all["nodes"].toList().map { it["id"].asString() }
        assertThat(ids).contains(a.toString(), b.toString())
        assertThat(ids).isSorted()
        val desc = graphql("{ shipmentMethods(orderBy: {direction: DESC}) { nodes { id } } }")["data"]["shipmentMethods"]["nodes"]
            .toList().map { it["id"].asString() }
        assertThat(desc).isEqualTo(ids.reversed())
        val archived = graphql("{ shipmentMethods(filter: {isArchived: true}) { totalCount nodes { id isArchived } } }")["data"]["shipmentMethods"]
        assertThat(archived["nodes"].toList().map { it["isArchived"].asBoolean() }).containsOnly(true)
        val active = graphql("{ shipmentMethods(filter: {isArchived: false}) { totalCount } }")["data"]["shipmentMethods"]
        assertThat(archived["totalCount"].asInt() + active["totalCount"].asInt()).isEqualTo(total)
        val page = graphql("{ shipmentMethods(first: 1, skip: 1) { hasNextPage nodes { id } } }")["data"]["shipmentMethods"]
        assertThat(page["nodes"].toList().map { it["id"].asString() }).containsExactly(ids[1])
        assertThat(page["hasNextPage"].asBoolean()).isEqualTo(total > 2)
        assertThat(errorMessage(graphql("{ shipmentMethods(first: 0) { nodes { id } } }")))
            .isEqualTo("Exception while fetching data (/shipmentMethods/nodes) : Limit must be greater than 0.")

        // externalReference needs an employee; the error nulls the whole (non-null) result
        val anonymous = graphql("{ shipmentMethods(first: 1) { nodes { id externalReference } } }")
        assertThat(errorMessage(anonymous))
            .isEqualTo("Exception while fetching data (/shipmentMethods/nodes[0]/externalReference) : Unauthorized access")
        assertThat(anonymous.has("data")).isFalse()
        val asEmployee = graphql("""{ shipmentMethod(id: "$a") { externalReference } }""", user = employee)
        assertThat(asEmployee["data"]["shipmentMethod"]["externalReference"].asString()).isEqualTo("ra")

        val unknown = graphql("""{ shipmentMethod(id: "${UUID.randomUUID()}") { id } }""")
        assertThat(errorMessage(unknown)).isEqualTo("Exception while fetching data (/shipmentMethod) : null")
        val invalid = graphql("""{ shipmentMethod(id: "nope") { id } }""")
        assertThat(errorMessage(invalid)).startsWith("Validation error (WrongType@[shipmentMethod])")

        val entities = graphql(
            """{ _entities(representations: [{__typename: "ShipmentMethod", id: "$a"}]) { ... on ShipmentMethod { id name } } }"""
        )
        assertThat(entities["data"]["_entities"][0]["name"].asString()).isEqualTo("conn-a")
        // no Apollo federated tracing: project decision (docs/PORTING.md §7), unlike the original
        assertThat(entities.path("extensions").has("ftv1")).isFalse()
    }

    @Test
    fun `fee calculation matches the original`() {
        val p1 = pvv(1.25)
        val p2 = pvv(0.333)
        val m1 = createMethod("fees-std", "ref-std", 500, 20, 150)
        val m2 = createMethod("fees-cheap", "ref-cheap", 1, 1, 1)
        graphql("""mutation { archiveShipmentMethod(input: {id: "$m2"}) { id } }""", user = employee)

        fun fees(items: String) = graphql("{ calculateShipmentFees(input: {items: [$items]}) }")
        val result = fees(
            """{productVariantVersionId: "$p1", quantity: 3, shipmentMethodId: "$m1"}, {productVariantVersionId: "$p2", quantity: 2, shipmentMethodId: "$m1"}, {productVariantVersionId: "$p2", quantity: 1, shipmentMethodId: "$m2"}"""
        )
        // oracle: 1264 (archived m2 accepted)
        assertThat(result["data"]["calculateShipmentFees"].asInt()).isEqualTo(1264)
        assertThat(fees("")["data"]["calculateShipmentFees"].asInt()).isEqualTo(0)
        assertThat(errorMessage(fees("""{productVariantVersionId: "$m1", quantity: 3, shipmentMethodId: "$m1"}""")))
            .isEqualTo("Exception while fetching data (/calculateShipmentFees) : Not all product variant versions are valid")
        assertThat(errorMessage(fees("""{productVariantVersionId: "$p1", quantity: 0, shipmentMethodId: "$m1"}""")))
            .isEqualTo("Exception while fetching data (/calculateShipmentFees) : Quantity must be greater than 0")
        assertThat(errorMessage(fees("""{productVariantVersionId: "$p1", quantity: 1, shipmentMethodId: "$p1"}""")))
            .isEqualTo("Exception while fetching data (/calculateShipmentFees) : Not all shipment methods are valid")

        val single = graphql("""{ shipmentMethod(id: "$m1") { calculateFees(items: [{productVariantVersionId: "$p1", quantity: 3}]) } }""")
        assertThat(single["data"]["shipmentMethod"]["calculateFees"].asInt()).isEqualTo(1122)
    }

    @Test
    fun `payment enabled creates one shipment per method, registers them with the provider and publishes created events`() {
        val address = userAddress()
        val p1 = pvv(1.25)
        val p2 = pvv(0.333)
        val m1 = createMethod("order-std", "ref-std", 500, 20, 150)
        val m2 = createMethod("order-cheap", "ref-cheap", 1, 1, 1)
        dapr.reset()
        val orderId = UUID.randomUUID()
        val i1 = Item(UUID.randomUUID(), p1, 2, m1)
        val i2 = Item(UUID.randomUUID(), p2, 1, m2)
        val i3 = Item(UUID.randomUUID(), p2, 4, m1)
        assertThat(paymentEnabled(orderJson(orderId, address, listOf(i1, i2, i3)))).isEqualTo(200)

        val created = dapr.published("shipment/shipment/created")
        assertThat(created).hasSize(2)
        val s1 = created[0].json["id"].asString()
        val s2 = created[1].json["id"].asString()
        assertThat(created[0].body).isEqualTo(
            """{"id":"$s1","orderId":"$orderId","status":"PENDING","orderItemIds":["${i1.id}","${i3.id}"],"shipmentMethodId":"$m1","shipmentAddressId":"$address"}"""
        )
        assertThat(created[1].body).isEqualTo(
            """{"id":"$s2","orderId":"$orderId","status":"PENDING","orderItemIds":["${i2.id}"],"shipmentMethodId":"$m2","shipmentAddressId":"$address"}"""
        )
        assertThat(provider.requests.map { it.body }).containsExactly(
            """{"shipmentId":"$s1","ref":"ref-std","quantity":6,"weight":3.832,"address":{"street1":"u1","street2":"u2","city":"uc","postalCode":"up","country":"de","companyName":null}}""",
            """{"shipmentId":"$s2","ref":"ref-cheap","quantity":1,"weight":0.333,"address":{"street1":"u1","street2":"u2","city":"uc","postalCode":"up","country":"de","companyName":null}}"""
        )
        assertThat(provider.requests.map { it.path }).containsOnly("/shipment/register")
        assertThat(provider.requests[0].contentType).startsWith("application/json")

        // federation: Order.shipments, sentItems, address type, OrderItem.sentWith
        val order = graphql(
            """{ _entities(representations: [{__typename: "Order", id: "$orderId"}, {__typename: "OrderItem", id: "${i3.id}"}, {__typename: "OrderItem", id: "$orderId"}]) {
                 ... on Order { id shipments { totalCount hasNextPage nodes { id status order { id } return { id } shipmentMethod { name } shipmentAddress { __typename id } sentItems(orderBy: {direction: DESC}) { totalCount nodes { id sentWith { id } } } } } }
                 ... on OrderItem { id sentWith { id status } } } }"""
        )
        assertThat(order["errors"]).isNull()
        val shipments = order["data"]["_entities"][0]["shipments"]
        assertThat(shipments["totalCount"].asInt()).isEqualTo(2)
        val first = shipments["nodes"].toList().single { it["id"].asString() == s1 }
        assertThat(first["status"].asString()).isEqualTo("PENDING")
        assertThat(first["order"]["id"].asString()).isEqualTo(orderId.toString())
        assertThat(first["return"].isNull).isTrue()
        assertThat(first["shipmentMethod"]["name"].asString()).isEqualTo("order-std")
        assertThat(first["shipmentAddress"]["__typename"].asString()).isEqualTo("UserAddress")
        assertThat(first["sentItems"]["totalCount"].asInt()).isEqualTo(2)
        assertThat(first["sentItems"]["nodes"].toList().map { it["id"].asString() })
            .containsExactlyElementsOf(listOf(i1.id, i3.id).map { it.toString() }.sortedDescending())
        assertThat(order["data"]["_entities"][1]["sentWith"]["id"].asString()).isEqualTo(s1)
        assertThat(order["data"]["_entities"][2]["sentWith"].isNull).isTrue()

        // the status filter never worked in the original (enum parameter cannot be encoded)
        val filtered = graphql(
            """{ _entities(representations: [{__typename: "Order", id: "$orderId"}]) { ... on Order { shipments(filter: {status: PENDING}) { totalCount nodes { id } } } } }"""
        )
        assertThat(filtered["errors"].toList().map { it["message"].asString() }).containsExactlyInAnyOrder(
            "Exception while fetching data (/_entities[0]/shipments/totalCount) : Cannot encode parameter of type io.r2dbc.spi.Parameters\$InParameter (PENDING)",
            "Exception while fetching data (/_entities[0]/shipments/nodes) : Cannot encode parameter of type io.r2dbc.spi.Parameters\$InParameter (PENDING)"
        )

        // provider callback
        dapr.reset()
        webTestClient.post().uri("/shipment/$s1/status").header("Content-Type", "application/json")
            .bodyValue("""{"status":"DELIVERED"}""").exchange().expectStatus().isOk.expectBody().isEmpty
        val updated = dapr.published("shipment/shipment/status-updated").single()
        assertThat(updated.json["id"].asString()).isEqualTo(s1)
        assertThat(updated.json.propertyNames().asSequence().toList()).containsExactly("id", "orderItemIds", "orderId", "status")
        assertThat(updated.json["orderItemIds"].toList().map { it.asString() }).containsExactlyInAnyOrder(i1.id.toString(), i3.id.toString())
        assertThat(updated.json["status"].asString()).isEqualTo("DELIVERED")
        webTestClient.post().uri("/shipment/${UUID.randomUUID()}/status").header("Content-Type", "application/json")
            .bodyValue("""{"status":"DELIVERED"}""").exchange().expectStatus().is5xxServerError
        webTestClient.post().uri("/shipment/$s1/status").header("Content-Type", "application/json")
            .bodyValue("""{"status":"LOST"}""").exchange().expectStatus().isBadRequest

        // a second shipment of an item keeps sentWith (ON CONFLICT DO NOTHING)
        val orderId2 = UUID.randomUUID()
        assertThat(paymentEnabled(orderJson(orderId2, address, listOf(i1)))).isEqualTo(200)
        assertThat(count("SELECT COUNT(*) FROM OrderItemEntity WHERE id = '${i1.id}' AND sentWithId = '$s1'")).isEqualTo(1)
    }

    @Test
    fun `failures during shipment creation publish creation-failed`() {
        val address = userAddress()
        val p1 = pvv(1.0)
        val m1 = createMethod("fail-std", "ref-fail", 1, 1, 1)
        dapr.reset()

        // unknown address: nothing stored
        val o1 = UUID.randomUUID()
        val unknownAddress = UUID.randomUUID()
        val item1 = Item(UUID.randomUUID(), p1, 1, m1)
        assertThat(paymentEnabled(orderJson(o1, unknownAddress, listOf(item1)))).isEqualTo(200)
        assertThat(dapr.published("shipment/shipment/creation-failed").single().body).isEqualTo(
            """{"orderId":"$o1","orderItemIds":["${item1.id}"],"shipmentMethodId":"$m1","shipmentAddressId":"$unknownAddress","reason":"Address does not exist"}"""
        )
        assertThat(shipmentIdsOf(o1)).isEmpty()

        // unknown shipment method: NoSuchElementException without message
        dapr.reset()
        val o2 = UUID.randomUUID()
        val unknownMethod = UUID.randomUUID()
        assertThat(paymentEnabled(orderJson(o2, address, listOf(Item(UUID.randomUUID(), p1, 1, unknownMethod))))).isEqualTo(200)
        assertThat(dapr.published("shipment/shipment/creation-failed").single().json["reason"].asString()).isEqualTo("Unknown reason")

        // unknown product variant version: the PENDING shipment stays (quirk of the original)
        dapr.reset()
        val o3 = UUID.randomUUID()
        assertThat(paymentEnabled(orderJson(o3, address, listOf(Item(UUID.randomUUID(), UUID.randomUUID(), 1, m1))))).isEqualTo(200)
        assertThat(dapr.published("shipment/shipment/creation-failed").single().json["reason"].asString())
            .isEqualTo("Not all product variant versions are valid")
        assertThat(dapr.published("shipment/shipment/created")).isEmpty()
        assertThat(shipmentIdsOf(o3)).hasSize(1)
        assertThat(provider.requests).isEmpty()
    }

    @Test
    fun `provider retries follow the experiment-config variables`() {
        val address = userAddress()
        val p1 = pvv(1.0)
        val m1 = createMethod("retry-std", "ref-retry", 1, 1, 1)
        assertThat(setEcs("""{"providerRetries":2,"providerRetryDelay":10}""")).isEqualTo(200)

        // two failures, third attempt succeeds
        provider.failNext.set(2)
        val o1 = UUID.randomUUID()
        assertThat(paymentEnabled(orderJson(o1, address, listOf(Item(UUID.randomUUID(), p1, 1, m1))))).isEqualTo(200)
        assertThat(provider.requests).hasSize(3)
        assertThat(dapr.published("shipment/shipment/created")).hasSize(1)

        // always failing: retries + 1 attempts, then creation-failed; the shipment row stays PENDING
        provider.reset()
        dapr.reset()
        provider.alwaysFail = true
        val o2 = UUID.randomUUID()
        assertThat(paymentEnabled(orderJson(o2, address, listOf(Item(UUID.randomUUID(), p1, 1, m1))))).isEqualTo(200)
        assertThat(provider.requests).hasSize(3)
        assertThat(dapr.published("shipment/shipment/created")).isEmpty()
        val failed = dapr.published("shipment/shipment/creation-failed").single().json
        assertThat(failed["orderId"].asString()).isEqualTo(o2.toString())
        assertThat(failed["reason"].asString()).contains("500")
        assertThat(count("SELECT COUNT(*) FROM ShipmentEntity WHERE orderId = '$o2' AND status = 'PENDING'")).isEqualTo(1)

        // no retries
        provider.reset()
        provider.alwaysFail = true
        assertThat(setEcs("""{"providerRetries":0}""")).isEqualTo(200)
        assertThat(paymentEnabled(orderJson(UUID.randomUUID(), address, listOf(Item(UUID.randomUUID(), p1, 1, m1))))).isEqualTo(200)
        assertThat(provider.requests).hasSize(1)
    }

    @Test
    fun `return creates a shipment to the newest vendor address with the cheapest (even archived) method`() {
        val address = userAddress()
        vendorAddress()
        val vendor = vendorAddress(companyName = "ACME")
        val p1 = pvv(1.25)
        val m1 = createMethod("ret-std", "ref-std", 500, 20, 150)
        val cheapest = createMethod("ret-cheapest", "ref-cheapest", -1_000_000, 0, 0)
        graphql("""mutation { archiveShipmentMethod(input: {id: "$cheapest"}) { id } }""", user = employee)
        val orderId = UUID.randomUUID()
        val i1 = Item(UUID.randomUUID(), p1, 2, m1)
        val i2 = Item(UUID.randomUUID(), p1, 1, m1)
        assertThat(paymentEnabled(orderJson(orderId, address, listOf(i1, i2)))).isEqualTo(200)
        provider.reset()
        dapr.reset()

        val returnId = UUID.randomUUID()
        assertThat(returnCreated("""{"id":"$returnId","orderId":"$orderId","orderItemIds":["${i1.id}","${i2.id}"],"reason":"x","refundedAmount":1,"createdAt":"2024"}"""))
            .isEqualTo(200)
        val created = dapr.published("shipment/shipment/created").single()
        val shipmentId = created.json["id"].asString()
        assertThat(created.json.has("orderId")).isFalse()
        assertThat(created.json["returnId"].asString()).isEqualTo(returnId.toString())
        assertThat(created.json["shipmentMethodId"].asString()).isEqualTo(cheapest.toString())
        assertThat(created.json["shipmentAddressId"].asString()).isEqualTo(vendor.toString())
        assertThat(created.json["orderItemIds"].toList().map { it.asString() }).containsExactlyInAnyOrder(i1.id.toString(), i2.id.toString())
        assertThat(provider.requests.single().body).isEqualTo(
            """{"shipmentId":"$shipmentId","ref":"ref-cheapest","quantity":3,"weight":3.75,"address":{"street1":"s1","street2":"s2","city":"c","postalCode":"p","country":"de","companyName":"ACME"}}"""
        )

        val result = graphql(
            """{ _entities(representations: [{__typename: "Return", id: "$returnId"}]) { ... on Return { id shipment { id order { id } return { id } shipmentAddress { __typename id } sentItems { totalCount } } } } }"""
        )
        val shipment = result["data"]["_entities"][0]["shipment"]
        assertThat(shipment["id"].asString()).isEqualTo(shipmentId)
        assertThat(shipment["order"].isNull).isTrue()
        assertThat(shipment["shipmentAddress"]["__typename"].asString()).isEqualTo("VendorAddress")
        assertThat(shipment["sentItems"]["totalCount"].asInt()).isEqualTo(2)

        val unknown = graphql("""{ _entities(representations: [{__typename: "Return", id: "${UUID.randomUUID()}"}]) { ... on Return { shipment { id } } } }""")
        assertThat(errorMessage(unknown))
            .startsWith("Exception while fetching data (/_entities[0]/shipment) : Cannot invoke \"org.misarch.shipment.persistence.model.ShipmentEntity.toDTO()\"")

        // unknown order items only: 500, nothing published
        dapr.reset()
        assertThat(returnCreated("""{"id":"${UUID.randomUUID()}","orderId":"$orderId","orderItemIds":["${UUID.randomUUID()}"]}""")).isEqualTo(500)
        assertThat(dapr.published).isEmpty()
    }

    @Test
    fun `address stubs cannot be resolved, invalid Authorized-User header is a 400`() {
        val result = graphql("""{ _entities(representations: [{__typename: "UserAddress", id: "${UUID.randomUUID()}"}]) { __typename } }""")
        assertThat(errorMessage(result)).startsWith("Unable to resolve federated type, representation={__typename=UserAddress, id=")
        webTestClient.post().uri("/graphql")
            .header("Content-Type", "application/json")
            .header("Authorized-User", """{"id":"x"}""")
            .bodyValue(mapOf("query" to "{ shipmentMethods { totalCount } }"))
            .exchange()
            .expectStatus().isBadRequest
    }
}
