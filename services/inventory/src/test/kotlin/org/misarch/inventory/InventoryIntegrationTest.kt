package org.misarch.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.misarch.common.testing.MisarchIntegrationTest
import org.misarch.common.nest.ProcessTerminator
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import tools.jackson.databind.JsonNode
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Callable

/** Records fire-and-forget failures instead of terminating the test JVM. */
class RecordingTerminator : ProcessTerminator {
    val errors: MutableList<Throwable> = CopyOnWriteArrayList()
    override fun terminate(error: Throwable) {
        errors += error
    }
}

@TestConfiguration
class TestTerminatorConfiguration {
    @Bean
    @Primary
    fun recordingTerminator() = RecordingTerminator()
}

/**
 * Behaviour of the inventory port against Postgres (Testcontainers) and a fake Dapr sidecar. Expected texts and
 * shapes were recorded from the running original (docs/specs/inventory.md §9, §11).
 */
@Import(TestTerminatorConfiguration::class)
class InventoryIntegrationTest : MisarchIntegrationTest() {

    @org.springframework.beans.factory.annotation.Autowired
    lateinit var terminator: RecordingTerminator

    private val admin = authorizedUserHeader(UUID.randomUUID(), "admin", "employee", "buyer")
    private val employee = authorizedUserHeader(UUID.randomUUID(), "employee")
    private val buyer = authorizedUserHeader(UUID.randomUUID(), "buyer")

    private fun post(route: String, body: String): Pair<Int, String> {
        val result = webTestClient.post().uri(route)
            .header("Content-Type", "application/cloudevents+json")
            .bodyValue(body)
            .exchange()
            .returnResult(String::class.java)
        val text = result.responseBody.collectList().block()!!.joinToString("")
        return result.status.value() to text
    }

    private fun event(route: String, data: String) = post(route, """{"id":"${UUID.randomUUID()}","topic":"t","data":$data}""")

    private fun newVariant(): String {
        val id = UUID.randomUUID().toString()
        assertThat(event("/product-variant-created", """{"id":"$id","productId":"${UUID.randomUUID()}"}""").first).isEqualTo(201)
        await { variantExists(id) }
        return id
    }

    private fun variantExists(id: String): Boolean {
        val r = graphql("""mutation { createProductItemBatch(input: {productVariantId: "$id", number: 0}) { id } }""", user = admin)
        return r["errors"] == null
    }

    private fun createItems(variant: String, n: Int): List<String> {
        val r = graphql("""mutation { createProductItemBatch(input: {productVariantId: "$variant", number: $n}) { id inventoryStatus orderId productVariant { id } } }""", user = admin)
        assertThat(r["errors"]).isNull()
        return r["data"]["createProductItemBatch"].values().map { it["id"].asString() }
    }

    private fun item(id: String): JsonNode =
        graphql("""{ productItem(id: "$id") { id inventoryStatus orderId productVariant { id } } }""", user = admin)["data"]["productItem"]

    private fun variantCount(variant: String, header: String? = null): JsonNode = graphql(
        """{ _entities(representations: [{__typename: "ProductVariant", id: "$variant"}]) { ... on ProductVariant { id inventoryCount } } }""",
        user = header
    )["data"]["_entities"][0]

    private fun phantomCount(): Int =
        graphql("""{ productItems(filter: {productVariant: null}) { totalCount } }""", user = admin)["data"]["productItems"]["totalCount"].asInt()

    private fun await(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("condition not met in time")
            Thread.sleep(50)
        }
    }

    private fun order(items: List<Pair<String, Int>>, id: String = UUID.randomUUID().toString()) = """
        {"id":"$id","userId":"${UUID.randomUUID()}","createdAt":"2026-10-13T12:00:00.123Z","orderStatus":"PLACED",
         "placedAt":"2026-10-13T12:00:01Z","rejectionReason":null,
         "orderItems":[${items.joinToString(",") { (pv, count) -> """{"id":"${UUID.randomUUID()}","createdAt":"2026-10-13T12:00:00.123Z","productVariantId":"$pv","productVariantVersionId":"${UUID.randomUUID()}","taxRateVersionId":"${UUID.randomUUID()}","shoppingCartItemId":"${UUID.randomUUID()}","count":$count,"compensatableAmount":100,"shipmentMethodId":"${UUID.randomUUID()}","discountIds":[]}""" }}],
         "shipmentAddressId":"${UUID.randomUUID()}","invoiceAddressId":"${UUID.randomUUID()}","compensatableOrderAmount":200,
         "paymentInformationId":"${UUID.randomUUID()}","paymentAuthorization":{"cVC":123},"vatNumber":null,"zExtra":{"b":1,"a":[1,2.5]}}
    """.trimIndent()

    // ---------------------------------------------------------------- runtime contract

    @Test
    fun `dapr subscribe equals the original verbatim`() {
        val body = webTestClient.get().uri("/dapr/subscribe").exchange().expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!
        val reference = File(System.getProperty("misarch.reference.dir"), "dapr-subscribe/inventory.json").readText().trim()
        assertThat(body).isEqualTo(reference)
    }

    @Test
    fun `health answers like the original`() {
        val body = webTestClient.get().uri("/health").exchange().expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!
        assertThat(body).isEqualTo("""{"status":"OK"}""")
    }

    // ---------------------------------------------------------------- mutations

    @Test
    fun `createProductItemBatch validates the variant case-sensitively and creates items`() {
        val variant = newVariant()
        val unknown = UUID.randomUUID().toString()
        val error = graphql("""mutation { createProductItemBatch(input: {productVariantId: "$unknown", number: 1}) { id } }""", user = admin)
        assertThat(error["data"].isNull).isTrue()
        val e = error["errors"][0]
        assertThat(e["message"].asString()).isEqualTo("ProductVariant with ID \"$unknown\" not found")
        assertThat(e["path"].values().map { it.asString() }).containsExactly("createProductItemBatch")
        assertThat(e["extensions"]["code"].asString()).isEqualTo("INTERNAL_SERVER_ERROR")
        assertThat(e["extensions"]["status"].asInt()).isEqualTo(404)
        assertThat(e["extensions"]["originalError"].toString())
            .isEqualTo("""{"message":"ProductVariant with ID \"$unknown\" not found","error":"Not Found","statusCode":404}""")

        val upper = graphql("""mutation { createProductItemBatch(input: {productVariantId: "${variant.uppercase()}", number: 1}) { id } }""", user = admin)
        assertThat(upper["errors"][0]["message"].asString()).isEqualTo("ProductVariant with ID \"${variant.uppercase()}\" not found")

        val none = graphql("""mutation { createProductItemBatch(input: {productVariantId: "$variant", number: -3}) { id } }""", user = admin)
        assertThat(none["data"]["createProductItemBatch"].size()).isEqualTo(0)

        val created = graphql("""mutation { createProductItemBatch(input: {productVariantId: "$variant", number: 3}) { id inventoryStatus orderId productVariant { id } } }""", user = admin)
        val items = created["data"]["createProductItemBatch"]
        assertThat(items.size()).isEqualTo(3)
        items.forEach {
            assertThat(it["inventoryStatus"].asString()).isEqualTo("IN_STORAGE")
            assertThat(it["orderId"].isNull).isTrue()
            assertThat(it["productVariant"]["id"].asString()).isEqualTo(variant)
        }
        assertThat(variantCount(variant)["inventoryCount"].asInt()).isEqualTo(3)
    }

    @Test
    fun `mutations require employee or admin, delete requires admin`() {
        val variant = newVariant()
        val forbidden = graphql("""mutation { createProductItemBatch(input: {productVariantId: "$variant", number: 1}) { id } }""", user = buyer)
        val e = forbidden["errors"][0]
        assertThat(e["message"].asString()).isEqualTo("Forbidden resource")
        assertThat(e["extensions"]["code"].asString()).isEqualTo("FORBIDDEN")
        assertThat(e["extensions"]["originalError"].toString()).isEqualTo("""{"message":"Forbidden resource","error":"Forbidden","statusCode":403}""")
        assertThat(graphql("""mutation { createProductItemBatch(input: {productVariantId: "$variant", number: 1}) { id } }""")["errors"][0]["message"].asString())
            .isEqualTo("Forbidden resource")

        val id = createItems(variant, 1)[0]
        val asEmployee = graphql("""mutation { deleteProductItem(id: "$id") { id } }""", user = employee)
        assertThat(asEmployee["errors"][0]["extensions"]["code"].asString()).isEqualTo("FORBIDDEN")
        val deleted = graphql("""mutation { deleteProductItem(id: "$id") { id inventoryStatus } }""", user = admin)
        assertThat(deleted["data"]["deleteProductItem"]["id"].asString()).isEqualTo(id)
        val missing = graphql("""mutation { deleteProductItem(id: "$id") { id } }""", user = admin)
        assertThat(missing["data"].isNull).isTrue()
        assertThat(missing["errors"][0]["message"].asString()).isEqualTo("ProductItem with ID \"$id\" not found") // FIXED
        assertThat(missing["errors"][0]["extensions"]["status"].asInt()).isEqualTo(404)
    }

    @Test
    fun `updateProductItem checks the variant first, keeps orderId and reports missing items`() {
        val variant = newVariant()
        val other = newVariant()
        val ids = createItems(variant, 2)
        val orderId = UUID.randomUUID().toString()
        graphql("""mutation { reserveProductItemBatch(input: {productVariantId: "$variant", number: 1, orderId: "$orderId"}) { id } }""", user = employee)
        val updated = graphql(
            """mutation { updateProductItem(input: {id: "${ids[0]}", productVariantId: "$other", inventoryStatus: SHIPPED}) { id inventoryStatus orderId productVariant { id } } }""",
            user = employee
        )["data"]["updateProductItem"]
        assertThat(updated["inventoryStatus"].asString()).isEqualTo("SHIPPED")
        assertThat(updated["orderId"].asString()).isEqualTo(orderId)
        assertThat(updated["productVariant"]["id"].asString()).isEqualTo(other)

        val unknownItem = UUID.randomUUID()
        val notFound = graphql("""mutation { updateProductItem(input: {id: "$unknownItem", productVariantId: "$other", inventoryStatus: SHIPPED}) { id } }""", user = admin)
        assertThat(notFound["errors"][0]["message"].asString()).isEqualTo("ProductItem with ID \"$unknownItem\" not found")
        val unknownVariant = UUID.randomUUID()
        val noVariant = graphql("""mutation { updateProductItem(input: {id: "$unknownItem", productVariantId: "$unknownVariant", inventoryStatus: SHIPPED}) { id } }""", user = admin)
        assertThat(noVariant["errors"][0]["message"].asString()).isEqualTo("ProductVariant with ID \"$unknownVariant\" not found")
    }

    @Test
    fun `reserveProductItemBatch returns the reserved items and reserves the oldest items`() {
        val variant = newVariant()
        val ids = createItems(variant, 4)
        val orderId = UUID.randomUUID().toString()
        val result = graphql("""mutation { reserveProductItemBatch(input: {productVariantId: "$variant", number: 2, orderId: "$orderId"}) { id inventoryStatus orderId } }""", user = admin)
        assertThat(result["errors"]).isNull()
        // FIXED: the reserved items (the original always returned [])
        val reservedItems = result["data"]["reserveProductItemBatch"].values()
        assertThat(reservedItems.map { it["id"].asString() }).containsExactlyInAnyOrder(ids[0], ids[1])
        assertThat(reservedItems).allSatisfy {
            assertThat(it["inventoryStatus"].asString()).isEqualTo("RESERVED")
            assertThat(it["orderId"].asString()).isEqualTo(orderId)
        }
        assertThat(item(ids[0])["inventoryStatus"].asString()).isEqualTo("RESERVED")
        assertThat(item(ids[1])["orderId"].asString()).isEqualTo(orderId)
        assertThat(item(ids[2])["inventoryStatus"].asString()).isEqualTo("IN_STORAGE")

        val tooMany = graphql("""mutation { reserveProductItemBatch(input: {productVariantId: "$variant", number: 5, orderId: "$orderId"}) { id } }""", user = admin)
        val e = tooMany["errors"][0]
        assertThat(e["message"].asString()).isEqualTo("Not enough product items available for product variant \"$variant\"")
        assertThat(e["extensions"]["status"].asInt()).isEqualTo(404)
        assertThat(variantCount(variant)["inventoryCount"].asInt()).isEqualTo(2)

        // FIXED: 0 / negative reserve nothing (the original's Mongo limit(0) reserved everything, -n reserved n)
        val zero = graphql("""mutation { reserveProductItemBatch(input: {productVariantId: "$variant", number: 0, orderId: "$orderId"}) { id } }""", user = admin)
        assertThat(zero["data"]["reserveProductItemBatch"].size()).isEqualTo(0)
        graphql("""mutation { reserveProductItemBatch(input: {productVariantId: "$variant", number: -1, orderId: "$orderId"}) { id } }""", user = admin)
        assertThat(variantCount(variant)["inventoryCount"].asInt()).isEqualTo(2)
    }

    @Test
    fun `concurrent reservations of the same variant take different items while stock lasts`() {
        // FIXED: the original (Mongo snapshot transaction) failed concurrent reservations with a WriteConflict
        // although enough items were available
        val variant = newVariant()
        createItems(variant, 20)
        val pool = Executors.newFixedThreadPool(6)
        try {
            val results = (1..6).map {
                pool.submit(Callable {
                    graphql("""mutation { reserveProductItemBatch(input: {productVariantId: "$variant", number: 3, orderId: "${UUID.randomUUID()}"}) { id } }""", user = admin)
                })
            }.map { it.get() }
            assertThat(results).allSatisfy { assertThat(it["errors"]).isNull() }
            val reserved = results.flatMap { r -> r["data"]["reserveProductItemBatch"].values().map { it["id"].asString() } }
            assertThat(reserved).hasSize(18).doesNotHaveDuplicates()
            assertThat(variantCount(variant)["inventoryCount"].asInt()).isEqualTo(2)
            // more demand than stock: exactly the stock is reserved, the rest fails with "Not enough"
            val more = (1..3).map {
                pool.submit(Callable {
                    graphql("""mutation { reserveProductItemBatch(input: {productVariantId: "$variant", number: 1, orderId: "${UUID.randomUUID()}"}) { id } }""", user = admin)
                })
            }.map { it.get() }
            assertThat(more.count { it["errors"] == null }).isEqualTo(2)
            assertThat(more.filter { it["errors"] != null }.map { it["errors"][0]["message"].asString() })
                .containsExactly("Not enough product items available for product variant \"$variant\"")
            assertThat(variantCount(variant)["inventoryCount"].asInt()).isEqualTo(0)
        } finally {
            pool.shutdown()
        }
    }

    // ---------------------------------------------------------------- queries

    @Test
    fun `productItems filters nodes like totalCount`() {
        val variant = newVariant()
        val created = createItems(variant, 3)
        graphql("""mutation { updateProductItem(input: {id: "${created[0]}", productVariantId: "$variant", inventoryStatus: SHIPPED}) { id } }""", user = admin)
        // FIXED: the original returned no nodes whenever a filter field was set
        val byVariant = graphql("""{ productItems(filter: {productVariant: "$variant"}) { nodes { id } totalCount hasNextPage } }""", user = admin)
        assertThat(byVariant["data"]["productItems"]["nodes"].values().map { it["id"].asString() }).containsExactlyElementsOf(created.sorted())
        assertThat(byVariant["data"]["productItems"]["totalCount"].asInt()).isEqualTo(3)
        assertThat(byVariant["data"]["productItems"]["hasNextPage"].asBoolean()).isFalse()

        val byBoth = graphql("""{ productItems(filter: {productVariant: "$variant", inventoryStatus: IN_STORAGE}) { nodes { id inventoryStatus } totalCount } }""", user = admin)
        assertThat(byBoth["data"]["productItems"]["nodes"].values().map { it["id"].asString() }).containsExactlyElementsOf(created.drop(1).sorted())
        assertThat(byBoth["data"]["productItems"]["totalCount"].asInt()).isEqualTo(2)

        val byStatus = graphql("""{ productItems(filter: {inventoryStatus: SHIPPED}) { nodes { id inventoryStatus } totalCount } }""", user = admin)
        assertThat(byStatus["data"]["productItems"]["nodes"].values()).allSatisfy { assertThat(it["inventoryStatus"].asString()).isEqualTo("SHIPPED") }
        assertThat(byStatus["data"]["productItems"]["nodes"].size()).isEqualTo(byStatus["data"]["productItems"]["totalCount"].asInt())

        val all = graphql("""{ productItems { nodes { id } totalCount hasNextPage } }""", user = admin)["data"]["productItems"]
        val ids = all["nodes"].values().map { it["id"].asString() }
        assertThat(ids).isSorted()
        assertThat(all["totalCount"].asInt()).isEqualTo(ids.size)
        assertThat(all["hasNextPage"].asBoolean()).isFalse()

        val page = graphql("""{ productItems(first: 2, skip: 1, orderBy: {direction: DESC}) { nodes { id } totalCount hasNextPage } }""", user = admin)["data"]["productItems"]
        assertThat(page["nodes"].values().map { it["id"].asString() }).isEqualTo(ids.reversed().drop(1).take(2))
        assertThat(page["hasNextPage"].asBoolean()).isEqualTo(3 < ids.size)
    }

    @Test
    fun `productItems validation, fragments and authorization errors look like the original`() {
        val bad = graphql("""{ productItems(skip: -1, first: 0) { totalCount } }""", user = admin)
        val e = bad["errors"][0]
        assertThat(e["message"].asString()).isEqualTo("Bad Request Exception")
        assertThat(e["extensions"].toString()).isEqualTo(
            """{"code":"BAD_REQUEST","originalError":{"message":["skip must not be less than 0","first must not be less than 1"],"error":"Bad Request","statusCode":400}}"""
        )
        // FIXED: fields selected through fragments are computed (the original ignored fragments: non-null error)
        val fragment = graphql("""{ productItems { ... on ProductItemConnection { totalCount } ...F } } fragment F on ProductItemConnection { hasNextPage }""", user = admin)
        assertThat(fragment["errors"]).isNull()
        assertThat(fragment["data"]["productItems"]["totalCount"].asInt()).isGreaterThanOrEqualTo(0)
        assertThat(fragment["data"]["productItems"]["hasNextPage"].asBoolean()).isFalse()

        assertThat(graphql("""{ productItems { totalCount } }""", user = buyer)["errors"][0]["message"].asString()).isEqualTo("Forbidden resource")
        assertThat(graphql("""{ productItems { totalCount } }""", user = """{"id":"x"}""")["errors"][0]["message"].asString())
            .isEqualTo("Cannot read properties of undefined (reading 'includes')")
        assertThat(graphql("""{ productItems { totalCount } }""", user = "notjson")["errors"][0]["message"].asString())
            .isEqualTo("Unexpected token o in JSON at position 1")
        assertThat(graphql("""{ productItems { totalCount } }""", user = """{"id":"x","roles":"superadmin"}""")["errors"]).isNull()

        val missing = UUID.randomUUID()
        val notFound = graphql("""{ productItem(id: "$missing") { id } }""", user = admin)["errors"][0]
        assertThat(notFound["message"].asString()).isEqualTo("ProductItem with ID \"$missing\" not found")
        assertThat(notFound["path"].values().map { it.asString() }).containsExactly("productItem")
    }

    // ---------------------------------------------------------------- federation

    @Test
    fun `ProductVariant entity serves inventoryCount publicly, productItems only to employees`() {
        val variant = newVariant()
        val ids = createItems(variant, 3)
        graphql("""mutation { updateProductItem(input: {id: "${ids[0]}", productVariantId: "$variant", inventoryStatus: SHIPPED}) { id } }""", user = admin)

        val anonymous = graphql(
            """{ _entities(representations: [{__typename: "ProductVariant", id: "$variant"}]) { ... on ProductVariant { id inventoryCount productItems { nodes { id } totalCount hasNextPage } } } }"""
        )
        val entity = anonymous["data"]["_entities"][0]
        assertThat(entity["id"].asString()).isEqualTo(variant)
        assertThat(entity["inventoryCount"].asInt()).isEqualTo(2)
        assertThat(entity["productItems"].isNull).isTrue()
        assertThat(anonymous["errors"][0]["message"].asString()).isEqualTo("Forbidden resource")
        assertThat(anonymous["errors"][0]["path"].values().map { it.asString() }).containsExactly("_entities", "0", "productItems")

        val asAdmin = graphql(
            """{ _entities(representations: [{__typename: "ProductVariant", id: "$variant"}]) { ... on ProductVariant { productItems(filter: {inventoryStatus: RESERVED}) { nodes { id } totalCount hasNextPage } } } }""",
            user = admin
        )["data"]["_entities"][0]["productItems"]
        // FIXED: the items of the variant filtered by status (the original: no nodes, totalCount of all statuses)
        assertThat(asAdmin["nodes"].size()).isEqualTo(0)
        assertThat(asAdmin["totalCount"].asInt()).isEqualTo(0)
        val inStorage = graphql(
            """{ _entities(representations: [{__typename: "ProductVariant", id: "$variant"}]) { ... on ProductVariant { productItems(filter: {inventoryStatus: IN_STORAGE}) { nodes { id } totalCount } all: productItems { totalCount } } } }""",
            user = admin
        )["data"]["_entities"][0]
        assertThat(inStorage["productItems"]["nodes"].values().map { it["id"].asString() }).containsExactlyElementsOf(ids.drop(1).sorted())
        assertThat(inStorage["productItems"]["totalCount"].asInt()).isEqualTo(2)
        assertThat(inStorage["all"]["totalCount"].asInt()).isEqualTo(3)
    }

    @Test
    fun `ProductItem entity resolution is guarded and reports errors at the entity path`() {
        val variant = newVariant()
        val id = createItems(variant, 1)[0]
        val anonymous = graphql("""{ _entities(representations: [{__typename: "ProductItem", id: "$id"}]) { ... on ProductItem { id inventoryStatus } } }""")
        assertThat(anonymous["data"]["_entities"][0].isNull).isTrue()
        assertThat(anonymous["errors"].size()).isEqualTo(1)
        assertThat(anonymous["errors"][0]["message"].asString()).isEqualTo("Forbidden resource")
        assertThat(anonymous["errors"][0]["path"].values().map { it.asString() }).containsExactly("_entities", "0")
        assertThat(anonymous["errors"][0]["extensions"]["code"].asString()).isEqualTo("FORBIDDEN")

        val ok = graphql("""{ _entities(representations: [{__typename: "ProductItem", id: "$id"}]) { ... on ProductItem { id inventoryStatus } } }""", user = admin)
        assertThat(ok["data"]["_entities"][0]["id"].asString()).isEqualTo(id)
    }

    // ---------------------------------------------------------------- events

    @Test
    fun `order created with stock reserves and publishes the order verbatim`() {
        val variant = newVariant()
        createItems(variant, 3)
        val orderId = UUID.randomUUID().toString()
        val order = order(listOf(variant to 2), orderId)
        val (status, body) = event("/order-created", order)
        assertThat(status).isEqualTo(201)
        assertThat(body).isEmpty()
        val published = dapr.awaitPublished("inventory/product-item/reservation-succeeded")
            .filter { it.json["order"]["id"].asString() == orderId }
        assertThat(published).hasSize(1)
        assertThat(published[0].pubsubName).isEqualTo("pubsub")
        assertThat(published[0].json["order"]).isEqualTo(mapper.readTree(order))
        assertThat(published[0].body).contains("\"rejectionReason\":null").contains("\"zExtra\":{\"b\":1,\"a\":[1,2.5]}")
        assertThat(variantCount(variant)["inventoryCount"].asInt()).isEqualTo(1)

        // FIXED: a redelivered order event reserves nothing more (the original reserved a second set) and
        // re-publishes the result
        assertThat(event("/order-created", order).first).isEqualTo(201)
        assertThat(variantCount(variant)["inventoryCount"].asInt()).isEqualTo(1)
        assertThat(dapr.awaitPublished("inventory/product-item/reservation-succeeded", 2)
            .filter { it.json["order"]["id"].asString() == orderId }).hasSize(2)

        // payment enabled -> IN_FULFILLMENT, shipment created -> SHIPPED, status updated -> DELIVERED / LOST
        assertThat(event("/payment-enabled", """{"order":{"id":"$orderId"}}""").first).isEqualTo(201)
        val reserved = graphql("""{ productItems { nodes { id inventoryStatus orderId } } }""", user = admin)["data"]["productItems"]["nodes"]
            .filter { !it["orderId"].isNull && it["orderId"].asString() == orderId }
        assertThat(reserved).hasSize(2).allSatisfy { assertThat(it["inventoryStatus"].asString()).isEqualTo("IN_FULFILLMENT") }
        val statusOf = { reserved.map { item(it["id"].asString())["inventoryStatus"].asString() }.distinct() }
        assertThat(event("/shipment-created", """{"id":"s","orderId":"$orderId","returnId":null,"status":"PENDING","orderItemIds":[],"shipmentMethodId":"m","shipmentAddressId":"a"}""").first).isEqualTo(201)
        assertThat(statusOf()).containsExactly("SHIPPED")
        event("/shipment-status-updated", """{"id":"s","orderId":"$orderId","returnId":null,"status":"IN_PROGRESS","orderItemIds":[]}""")
        assertThat(statusOf()).containsExactly("SHIPPED")
        event("/shipment-status-updated", """{"id":"s","orderId":"$orderId","returnId":null,"status":"FAILED","orderItemIds":[]}""")
        assertThat(statusOf()).containsExactly("LOST")
        event("/shipment-status-updated", """{"id":"s","orderId":"$orderId","returnId":null,"status":"DELIVERED","orderItemIds":[]}""")
        assertThat(statusOf()).containsExactly("DELIVERED")
        // return shipment (no orderId): nothing happens
        assertThat(event("/shipment-created", """{"id":"s","orderId":null,"returnId":"r","status":"PENDING","orderItemIds":[]}""").first).isEqualTo(201)

        // payment failed releases everything of the order, whatever its status (FIXED: awaited before the answer)
        assertThat(event("/payment-failed", """{"order":{"id":"$orderId"}}""").first).isEqualTo(201)
        assertThat(reserved.all { item(it["id"].asString())["inventoryStatus"].asString() == "IN_STORAGE" }).isTrue()
        assertThat(item(reserved[0]["id"].asString())["orderId"].isNull).isTrue()
    }

    @Test
    fun `order created with insufficient stock publishes reservation-failed and releases`() {
        val variant = newVariant()
        val short = newVariant()
        createItems(variant, 2)
        val orderId = UUID.randomUUID().toString()
        val (status, body) = event("/order-created", order(listOf(variant to 1, short to 1), orderId))
        assertThat(status).isEqualTo(201)
        val released = mapper.readTree(body)
        assertThat(released.size()).isEqualTo(1)
        assertThat(released[0].propertyNames().asSequence().toList()).containsExactly("_id", "productVariant", "inventoryStatus", "orderId", "id")
        assertThat(released[0]["inventoryStatus"].asString()).isEqualTo("IN_STORAGE")
        assertThat(released[0]["orderId"].isNull).isTrue()
        val failed = dapr.awaitPublished("inventory/product-item/reservation-failed").filter { it.json["order"]["id"].asString() == orderId }
        assertThat(failed).hasSize(1)
        assertThat(failed[0].json["failedProductVariantIds"].values().map { it.asString() }).containsExactly(short)
        assertThat(variantCount(variant)["inventoryCount"].asInt()).isEqualTo(2)
    }

    @Test
    fun `releasing an order without items inserts no phantom item`() {
        // FIXED: the original's release upsert inserted an IN_STORAGE item without product variant
        val short = newVariant()
        val before = phantomCount()
        val (status, body) = event("/order-created", order(listOf(short to 1)))
        assertThat(status).isEqualTo(201)
        assertThat(body).isEqualTo("[]")
        assertThat(event("/discount-validation-failed", """{"order":{"id":"${UUID.randomUUID()}"},"failingDiscountIds":[]}""").first).isEqualTo(201)
        assertThat(event("/payment-failed", """{"order":{"id":"${UUID.randomUUID()}"}}""").first).isEqualTo(201)
        assertThat(phantomCount()).isEqualTo(before)
        assertThat(before).isEqualTo(0)
        val withVariant = graphql("""{ productItems { nodes { id productVariant { id } } totalCount } }""", user = admin)
        assertThat(withVariant["errors"]).isNull()
    }

    @Test
    fun `a failing publish of the reservation result is redelivered and stays idempotent`() {
        val variant = newVariant()
        createItems(variant, 2)
        val orderId = UUID.randomUUID().toString()
        val order = order(listOf(variant to 1), orderId)
        dapr.publishStatus = 500
        try {
            // FIXED: the original published fire-and-forget (a failed publish was only logged, the saga stopped)
            assertThat(event("/order-created", order).first).isEqualTo(500)
        } finally {
            dapr.publishStatus = 204
        }
        assertThat(variantCount(variant)["inventoryCount"].asInt()).isEqualTo(1)
        assertThat(event("/order-created", order).first).isEqualTo(201)
        assertThat(variantCount(variant)["inventoryCount"].asInt()).isEqualTo(1)
        // the fake sidecar records the rejected attempt too: one rejected + one accepted publish
        assertThat(dapr.awaitPublished("inventory/product-item/reservation-succeeded", 2).filter { it.json["order"]["id"].asString() == orderId }).hasSize(2)
    }

    @Test
    fun `deleteProductItem of a missing item is a not-found error`() {
        val missing = UUID.randomUUID()
        val result = graphql("""mutation { deleteProductItem(id: "$missing") { id } }""", user = admin)
        // FIXED: the original returned null for the non-null field ("Cannot return null …")
        assertThat(result["errors"][0]["message"].asString()).isEqualTo("ProductItem with ID \"$missing\" not found")
    }

    @Test
    fun `invalid events answer like the NestJS validation pipe`() {
        val (status, body) = event("/order-created", """{"id":"x","orderItems":[{"count":0}]}""")
        assertThat(status).isEqualTo(400)
        val json = mapper.readTree(body)
        assertThat(json.propertyNames().asSequence().toList()).containsExactly("message", "error", "statusCode")
        assertThat(json["message"][0].asString()).isEqualTo("id must be a UUID")
        assertThat(json["statusCode"].asInt()).isEqualTo(400)
        assertThat(event("/payment-enabled", "{}").second)
            .isEqualTo("""{"message":["order must be an object"],"error":"Bad Request","statusCode":400}""")
        assertThat(post("/discount-validation-failed", """{"id":"1"}""")).isEqualTo(500 to """{"statusCode":500,"message":"Internal server error"}""")
        assertThat(post("/product-variant-created", """{"id":"1"}""").first).isEqualTo(500)
        assertThat(event("/shipment-created", """{"id":"s","orderId":7,"status":"X","orderItemIds":[]}""").second)
            .isEqualTo("""{"message":["orderId must be a string","status must be one of the following values: PENDING, IN_PROGRESS, DELIVERED, FAILED"],"error":"Bad Request","statusCode":400}""")
    }

    @Test
    fun `a duplicate product variant event is acknowledged without terminating the process`() {
        // FIXED: the original created the partial fire-and-forget and the duplicate key terminated the process
        val variant = newVariant()
        val before = terminator.errors.size
        assertThat(event("/product-variant-created", """{"id":"$variant"}""").first).isEqualTo(201)
        Thread.sleep(300)
        assertThat(terminator.errors.size).isEqualTo(before)
        assertThat(variantExists(variant)).isTrue()
    }
}
