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
        assertThat(missing["errors"][0]["message"].asString()).isEqualTo("Cannot return null for non-nullable field Mutation.deleteProductItem.")
        assertThat(missing["errors"][0]["extensions"].toString()).isEqualTo("""{"code":"INTERNAL_SERVER_ERROR"}""")
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
    fun `reserveProductItemBatch returns an empty list and reserves the oldest items`() {
        val variant = newVariant()
        val ids = createItems(variant, 4)
        val orderId = UUID.randomUUID().toString()
        val result = graphql("""mutation { reserveProductItemBatch(input: {productVariantId: "$variant", number: 2, orderId: "$orderId"}) { id } }""", user = admin)
        assertThat(result["errors"]).isNull()
        assertThat(result["data"]["reserveProductItemBatch"].size()).isEqualTo(0)
        assertThat(item(ids[0])["inventoryStatus"].asString()).isEqualTo("RESERVED")
        assertThat(item(ids[1])["orderId"].asString()).isEqualTo(orderId)
        assertThat(item(ids[2])["inventoryStatus"].asString()).isEqualTo("IN_STORAGE")

        val tooMany = graphql("""mutation { reserveProductItemBatch(input: {productVariantId: "$variant", number: 5, orderId: "$orderId"}) { id } }""", user = admin)
        val e = tooMany["errors"][0]
        assertThat(e["message"].asString()).isEqualTo("Not enough product items available for product variant \"$variant\"")
        assertThat(e["extensions"]["status"].asInt()).isEqualTo(404)
        assertThat(variantCount(variant)["inventoryCount"].asInt()).isEqualTo(2)

        // limit(0) = no limit: reserves everything that is left
        graphql("""mutation { reserveProductItemBatch(input: {productVariantId: "$variant", number: 0, orderId: "$orderId"}) { id } }""", user = admin)
        assertThat(variantCount(variant)["inventoryCount"].asInt()).isEqualTo(0)
    }

    @Test
    fun `concurrent reservations of the same items conflict like MongoDB transactions`() {
        val variant = newVariant()
        createItems(variant, 20)
        val pool = Executors.newFixedThreadPool(6)
        try {
            val results = (1..6).map {
                pool.submit(Callable {
                    graphql("""mutation { reserveProductItemBatch(input: {productVariantId: "$variant", number: 5, orderId: "${UUID.randomUUID()}"}) { id } }""", user = admin)
                })
            }.map { it.get() }
            val failures = results.filter { it["errors"] != null }
            assertThat(failures).isNotEmpty()
            failures.forEach {
                assertThat(it["errors"][0]["message"].asString()).isIn(
                    "WriteConflict error: this operation conflicted with another operation. Please retry your operation or multi-document transaction.",
                    "Not enough product items available for product variant \"$variant\""
                )
            }
            val succeeded = results.size - failures.size
            assertThat(variantCount(variant)["inventoryCount"].asInt()).isEqualTo(20 - 5 * succeeded)
        } finally {
            pool.shutdown()
        }
    }

    // ---------------------------------------------------------------- queries

    @Test
    fun `productItems reproduces the filter mapping quirk`() {
        val variant = newVariant()
        createItems(variant, 3)
        val byVariant = graphql("""{ productItems(filter: {productVariant: "$variant"}) { nodes { id } totalCount hasNextPage } }""", user = admin)
        assertThat(byVariant["data"]["productItems"]["nodes"].size()).isEqualTo(0)
        assertThat(byVariant["data"]["productItems"]["totalCount"].asInt()).isEqualTo(3)
        assertThat(byVariant["data"]["productItems"]["hasNextPage"].asBoolean()).isFalse()

        val byStatus = graphql("""{ productItems(filter: {inventoryStatus: IN_STORAGE}) { nodes { id } totalCount } }""", user = admin)
        assertThat(byStatus["data"]["productItems"]["nodes"].size()).isEqualTo(0)
        assertThat(byStatus["data"]["productItems"]["totalCount"].asInt()).isGreaterThanOrEqualTo(3)

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
        val fragment = graphql("""{ productItems { ... on ProductItemConnection { totalCount } } }""", user = admin)
        assertThat(fragment["errors"][0]["message"].asString()).isEqualTo("Cannot return null for non-nullable field ProductItemConnection.totalCount.")
        assertThat(fragment["data"].isNull).isTrue()

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
        assertThat(asAdmin["nodes"].size()).isEqualTo(0)
        assertThat(asAdmin["totalCount"].asInt()).isEqualTo(3) // filter ignored, all statuses
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

        // payment failed releases everything of the order, whatever its status (fire-and-forget)
        assertThat(event("/payment-failed", """{"order":{"id":"$orderId"}}""").first).isEqualTo(201)
        await { reserved.all { item(it["id"].asString())["inventoryStatus"].asString() == "IN_STORAGE" } }
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
    fun `releasing an order without items inserts a phantom item`() {
        val short = newVariant()
        val before = phantomCount()
        val (status, body) = event("/order-created", order(listOf(short to 1)))
        assertThat(status).isEqualTo(201)
        assertThat(body).isEqualTo("[]")
        assertThat(phantomCount()).isEqualTo(before + 1)
        assertThat(event("/discount-validation-failed", """{"order":{"id":"${UUID.randomUUID()}"},"failingDiscountIds":[]}""").first).isEqualTo(201)
        await { phantomCount() == before + 2 }
        // a phantom has no product variant: selecting it fails like the original (nodes becomes null)
        val withVariant = graphql("""{ productItems { nodes { id productVariant { id } } totalCount } }""", user = admin)
        assertThat(withVariant["errors"][0]["message"].asString()).isEqualTo("Cannot return null for non-nullable field ProductVariant.id.")
        assertThat(withVariant["data"]["productItems"]["nodes"].isNull).isTrue()
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
    fun `a duplicate product variant event is an unhandled rejection (process termination)`() {
        val variant = newVariant()
        val before = terminator.errors.size
        assertThat(event("/product-variant-created", """{"id":"$variant"}""").first).isEqualTo(201)
        await { terminator.errors.size > before }
    }
}
