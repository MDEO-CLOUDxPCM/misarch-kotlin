package org.misarch.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.misarch.common.testing.MisarchIntegrationTest
import org.misarch.common.testing.SqlStatementLog
import org.springframework.context.annotation.Import
import tools.jackson.databind.JsonNode
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Behaviour of the inventory service against a real Postgres (Testcontainers) and a fake Dapr sidecar.
 */
@Import(SqlStatementLog::class)
class InventoryIntegrationTest : MisarchIntegrationTest() {

    private val adminId = UUID.randomUUID()
    private val admin = authorizedUserHeader(adminId, "admin", "employee", "buyer")
    private val employeeId = UUID.randomUUID()
    private val employee = authorizedUserHeader(employeeId, "employee")
    private val buyerId = UUID.randomUUID()
    private val buyer = authorizedUserHeader(buyerId, "buyer")

    // ---------------------------------------------------------------- helpers

    private fun productVariant(id: UUID = UUID.randomUUID()): UUID {
        val status = deliverEvent(
            "/subscription/catalog/product-variant/created", "catalog/product-variant/created",
            """{"id":"$id","productId":"${UUID.randomUUID()}","isPubliclyVisible":true}"""
        )
        assertThat(status).isEqualTo(200)
        return id
    }

    private fun createItems(productVariantId: UUID, number: Int): List<UUID> {
        val result = graphql(
            """mutation { createProductItemBatch(input: {productVariantId: "$productVariantId", number: $number}) { id inventoryStatus orderId productVariant { id } } }""",
            user = employee
        )
        assertThat(result["errors"]).isNull()
        return result["data"]["createProductItemBatch"].values().map { UUID.fromString(it["id"].asString()) }
    }

    private fun item(id: UUID): JsonNode =
        graphql("""{ productItem(id: "$id") { id inventoryStatus orderId productVariant { id } } }""", user = employee)["data"]["productItem"]

    private fun inventoryCount(productVariantId: UUID): Int = graphql(
        """{ _entities(representations: [{__typename: "ProductVariant", id: "$productVariantId"}]) { ... on ProductVariant { inventoryCount } } }"""
    )["data"]["_entities"][0]["inventoryCount"].asInt()

    private fun reserve(productVariantId: UUID, number: Int, orderId: UUID = UUID.randomUUID()): JsonNode = graphql(
        """mutation { reserveProductItemBatch(input: {productVariantId: "$productVariantId", number: $number, orderId: "$orderId"}) { id inventoryStatus orderId } }""",
        user = employee
    )

    private fun orderJson(orderId: UUID, items: List<Pair<UUID, Int>>): String {
        val itemJson = items.joinToString(",") { (productVariantId, count) ->
            """{"id":"${UUID.randomUUID()}","createdAt":"2026-10-13T12:00:00.123Z","productVariantId":"$productVariantId","productVariantVersionId":"${UUID.randomUUID()}","taxRateVersionId":"${UUID.randomUUID()}","shoppingCartItemId":"${UUID.randomUUID()}","count":$count,"compensatableAmount":100,"shipmentMethodId":"${UUID.randomUUID()}","discountIds":[]}"""
        }
        return """{"id":"$orderId","userId":"${UUID.randomUUID()}","createdAt":"2026-10-13T12:00:00.123Z","orderStatus":"PLACED","placedAt":"2026-10-13T12:00:01Z","rejectionReason":null,"orderItems":[$itemJson],"shipmentAddressId":"${UUID.randomUUID()}","invoiceAddressId":"${UUID.randomUUID()}","compensatableOrderAmount":200,"paymentInformationId":"${UUID.randomUUID()}","paymentAuthorization":{"cvc":123},"vatNumber":null}"""
    }

    private fun orderCreated(order: String): Int =
        deliverEvent("/subscription/order/order/created", "order/order/created", order)

    private fun orderEvent(route: String, topic: String, orderId: UUID): Int =
        deliverEvent(route, topic, """{"order":{"id":"$orderId","orderItems":[]}}""")

    private fun shipmentEvent(route: String, topic: String, orderId: UUID?, status: String): Int = deliverEvent(
        route, topic,
        """{"id":"${UUID.randomUUID()}","orderId":${orderId?.let { "\"$it\"" } ?: "null"},"returnId":null,"status":"$status","orderItemIds":[]}"""
    )


    // ---------------------------------------------------------------- mutations

    @Test
    fun `createProductItemBatch creates items of a known product variant`() {
        val productVariantId = productVariant()
        val unknown = UUID.randomUUID()
        val error = graphql("""mutation { createProductItemBatch(input: {productVariantId: "$unknown", number: 1}) { id } }""", user = employee)
        assertThat(errorMessage(error)).isEqualTo("Exception while fetching data (/createProductItemBatch) : null")

        val none = graphql("""mutation { createProductItemBatch(input: {productVariantId: "$productVariantId", number: -3}) { id } }""", user = employee)
        assertThat(none["data"]["createProductItemBatch"].size()).isEqualTo(0)

        val created = graphql(
            """mutation { createProductItemBatch(input: {productVariantId: "$productVariantId", number: 3}) { id inventoryStatus orderId productVariant { id } } }""",
            user = employee
        )["data"]["createProductItemBatch"]
        assertThat(created.size()).isEqualTo(3)
        created.forEach {
            assertThat(it["inventoryStatus"].asString()).isEqualTo("IN_STORAGE")
            assertThat(it["orderId"].isNull).isTrue()
            assertThat(it["productVariant"]["id"].asString()).isEqualTo(productVariantId.toString())
        }
        assertThat(inventoryCount(productVariantId)).isEqualTo(3)
        // a redelivered product variant event keeps the variant and its items
        productVariant(productVariantId)
        assertThat(inventoryCount(productVariantId)).isEqualTo(3)
    }

    @Test
    fun `mutations require an employee, deleteProductItem an admin`() {
        val productVariantId = productVariant()
        val asBuyer = graphql("""mutation { createProductItemBatch(input: {productVariantId: "$productVariantId", number: 1}) { id } }""", user = buyer)
        assertThat(errorMessage(asBuyer)).isEqualTo("Exception while fetching data (/createProductItemBatch) : Unauthorized access: $buyerId is not an employee or admin")
        val anonymous = graphql("""mutation { createProductItemBatch(input: {productVariantId: "$productVariantId", number: 1}) { id } }""")
        assertThat(errorMessage(anonymous)).isEqualTo("Exception while fetching data (/createProductItemBatch) : Unauthorized access")

        val id = createItems(productVariantId, 1)[0]
        val asEmployee = graphql("""mutation { deleteProductItem(id: "$id") { id } }""", user = employee)
        assertThat(errorMessage(asEmployee)).endsWith("Unauthorized access: $employeeId is not an admin")
        val deleted = graphql("""mutation { deleteProductItem(id: "$id") { id inventoryStatus } }""", user = admin)
        assertThat(deleted["data"]["deleteProductItem"]["id"].asString()).isEqualTo(id.toString())
        val missing = graphql("""mutation { deleteProductItem(id: "$id") { id } }""", user = admin)
        assertThat(errorMessage(missing)).isEqualTo("Exception while fetching data (/deleteProductItem) : null")
    }

    @Test
    fun `updateProductItem checks the product variant and keeps the order`() {
        val productVariantId = productVariant()
        val other = productVariant()
        val ids = createItems(productVariantId, 1)
        val orderId = UUID.randomUUID()
        assertThat(reserve(productVariantId, 1, orderId)["errors"]).isNull()
        val updated = graphql(
            """mutation { updateProductItem(input: {id: "${ids[0]}", productVariantId: "$other", inventoryStatus: SHIPPED}) { id inventoryStatus orderId productVariant { id } } }""",
            user = employee
        )["data"]["updateProductItem"]
        assertThat(updated["inventoryStatus"].asString()).isEqualTo("SHIPPED")
        assertThat(updated["orderId"].asString()).isEqualTo(orderId.toString())
        assertThat(updated["productVariant"]["id"].asString()).isEqualTo(other.toString())

        val unknownItem = UUID.randomUUID()
        val notFound = graphql("""mutation { updateProductItem(input: {id: "$unknownItem", productVariantId: "$other", inventoryStatus: SHIPPED}) { id } }""", user = employee)
        assertThat(errorMessage(notFound)).endsWith(" : null")
        val unknownVariant = UUID.randomUUID()
        val noVariant = graphql("""mutation { updateProductItem(input: {id: "${ids[0]}", productVariantId: "$unknownVariant", inventoryStatus: SHIPPED}) { id } }""", user = employee)
        assertThat(errorMessage(noVariant)).endsWith(" : null")
    }

    @Test
    fun `reserveProductItemBatch reserves the oldest items, all or nothing`() {
        val productVariantId = productVariant()
        val ids = createItems(productVariantId, 4)
        val orderId = UUID.randomUUID()
        val result = reserve(productVariantId, 2, orderId)
        assertThat(result["errors"]).isNull()
        val reserved = result["data"]["reserveProductItemBatch"].values()
        assertThat(reserved.map { UUID.fromString(it["id"].asString()) }).containsExactlyInAnyOrder(ids[0], ids[1])
        assertThat(reserved).allSatisfy {
            assertThat(it["inventoryStatus"].asString()).isEqualTo("RESERVED")
            assertThat(it["orderId"].asString()).isEqualTo(orderId.toString())
        }
        assertThat(item(ids[2])["inventoryStatus"].asString()).isEqualTo("IN_STORAGE")

        val tooMany = reserve(productVariantId, 5, orderId)
        assertThat(errorMessage(tooMany)).isEqualTo(
            "Exception while fetching data (/reserveProductItemBatch) : Not enough product items available for product variant \"$productVariantId\""
        )
        assertThat(inventoryCount(productVariantId)).isEqualTo(2)

        assertThat(reserve(productVariantId, 0, orderId)["data"]["reserveProductItemBatch"].size()).isEqualTo(0)
        assertThat(reserve(productVariantId, -1, orderId)["data"]["reserveProductItemBatch"].size()).isEqualTo(0)
        assertThat(inventoryCount(productVariantId)).isEqualTo(2)
    }

    @Test
    fun `a reservation is one statement on the reservation index`() {
        val productVariantId = productVariant()
        createItems(productVariantId, 2)
        val statements = SqlStatementLog.record { assertThat(reserve(productVariantId, 1)["errors"]).isNull() }
        assertThat(statements.filter { it != "BEGIN" && it != "COMMIT" }).containsExactly(
            "UPDATE ProductItemEntity SET inventoryStatus = 'RESERVED', orderId = \$1 WHERE id IN (" +
                "SELECT id FROM ProductItemEntity WHERE productVariantId = \$2 AND inventoryStatus = 'IN_STORAGE' " +
                "ORDER BY insertionOrder LIMIT \$3 FOR UPDATE SKIP LOCKED) RETURNING *"
        )
    }

    @Test
    fun `concurrent reservations of the same variant take different items while stock lasts`() {
        val productVariantId = productVariant()
        createItems(productVariantId, 20)
        val pool = Executors.newFixedThreadPool(6)
        try {
            val results = (1..6).map { pool.submit(Callable { reserve(productVariantId, 3) }) }.map { it.get() }
            assertThat(results).allSatisfy { assertThat(it["errors"]).isNull() }
            val reserved = results.flatMap { r -> r["data"]["reserveProductItemBatch"].values().map { it["id"].asString() } }
            assertThat(reserved).hasSize(18).doesNotHaveDuplicates()
            assertThat(inventoryCount(productVariantId)).isEqualTo(2)
            val more = (1..3).map { pool.submit(Callable { reserve(productVariantId, 1) }) }.map { it.get() }
            assertThat(more.count { it["errors"] == null }).isEqualTo(2)
            assertThat(inventoryCount(productVariantId)).isEqualTo(0)
        } finally {
            pool.shutdown()
        }
    }

    // ---------------------------------------------------------------- queries

    @Test
    fun `productItems filters, orders and pages`() {
        val productVariantId = productVariant()
        val created = createItems(productVariantId, 3).map { it.toString() }
        graphql("""mutation { updateProductItem(input: {id: "${created[0]}", productVariantId: "$productVariantId", inventoryStatus: SHIPPED}) { id } }""", user = employee)

        val byVariant = graphql("""{ productItems(filter: {productVariant: "$productVariantId"}) { nodes { id } totalCount hasNextPage } }""", user = employee)["data"]["productItems"]
        assertThat(byVariant["nodes"].values().map { it["id"].asString() }).containsExactlyElementsOf(created.sorted())
        assertThat(byVariant["totalCount"].asInt()).isEqualTo(3)
        assertThat(byVariant["hasNextPage"].asBoolean()).isFalse()

        val byBoth = graphql("""{ productItems(filter: {productVariant: "$productVariantId", inventoryStatus: IN_STORAGE}) { nodes { id } totalCount } }""", user = employee)["data"]["productItems"]
        assertThat(byBoth["nodes"].values().map { it["id"].asString() }).containsExactlyElementsOf(created.drop(1).sorted())
        assertThat(byBoth["totalCount"].asInt()).isEqualTo(2)

        val page = graphql(
            """{ productItems(first: 1, skip: 1, orderBy: {direction: DESC}, filter: {productVariant: "$productVariantId"}) { nodes { id } totalCount hasNextPage } }""",
            user = employee
        )["data"]["productItems"]
        assertThat(page["nodes"].values().map { it["id"].asString() }).containsExactly(created.sortedDescending()[1])
        assertThat(page["hasNextPage"].asBoolean()).isTrue()

        val fragment = graphql("""{ productItems { ... on ProductItemConnection { totalCount } ...F } } fragment F on ProductItemConnection { hasNextPage }""", user = employee)
        assertThat(fragment["errors"]).isNull()

        val zero = graphql("""{ productItems(first: 0) { nodes { id } } }""", user = employee)
        assertThat(errorMessage(zero)).isEqualTo("Exception while fetching data (/productItems/nodes) : Limit must be greater than 0.")
        val negative = graphql("""{ productItems(skip: -1) { nodes { id } } }""", user = employee)
        assertThat(errorMessage(negative)).isEqualTo("Exception while fetching data (/productItems/nodes) : Offset must not be negative.")

        assertThat(errorMessage(graphql("""{ productItems { totalCount } }""", user = buyer))).endsWith("is not an employee or admin")
        val missing = UUID.randomUUID()
        assertThat(errorMessage(graphql("""{ productItem(id: "$missing") { id } }""", user = employee)))
            .isEqualTo("Exception while fetching data (/productItem) : null")
    }

    // ---------------------------------------------------------------- federation

    @Test
    fun `ProductVariant serves inventoryCount publicly with one COUNT, productItems only to employees`() {
        val productVariantId = productVariant()
        val ids = createItems(productVariantId, 3)
        graphql("""mutation { updateProductItem(input: {id: "${ids[0]}", productVariantId: "$productVariantId", inventoryStatus: SHIPPED}) { id } }""", user = employee)

        var anonymous: JsonNode? = null
        val statements = SqlStatementLog.record {
            anonymous = graphql(
                """{ _entities(representations: [{__typename: "ProductVariant", id: "$productVariantId"}]) { ... on ProductVariant { id inventoryCount } } }"""
            )
        }
        assertThat(anonymous!!["data"]["_entities"][0]["inventoryCount"].asInt()).isEqualTo(2)
        assertThat(statements).singleElement().satisfies({
            assertThat(it.lowercase()).startsWith("select count(").contains("productvariantid\" = \$1").contains("inventorystatus\" = \$2")
        })

        val forbidden = graphql(
            """{ _entities(representations: [{__typename: "ProductVariant", id: "$productVariantId"}]) { ... on ProductVariant { productItems { totalCount } } } }"""
        )
        assertThat(forbidden["data"]["_entities"][0]["productItems"].isNull).isTrue()
        assertThat(errorMessage(forbidden)).endsWith("Unauthorized access")

        val inStorage = graphql(
            """{ _entities(representations: [{__typename: "ProductVariant", id: "$productVariantId"}]) { ... on ProductVariant { productItems(filter: {inventoryStatus: IN_STORAGE}) { nodes { id } totalCount } all: productItems { totalCount } } } }""",
            user = employee
        )["data"]["_entities"][0]
        assertThat(inStorage["productItems"]["nodes"].values().map { it["id"].asString() })
            .containsExactlyElementsOf(ids.drop(1).map { it.toString() }.sorted())
        assertThat(inStorage["productItems"]["totalCount"].asInt()).isEqualTo(2)
        assertThat(inStorage["all"]["totalCount"].asInt()).isEqualTo(3)
    }

    @Test
    fun `ProductItem entities are resolved for employees only`() {
        val productVariantId = productVariant()
        val id = createItems(productVariantId, 1)[0]
        val query = """{ _entities(representations: [{__typename: "ProductItem", id: "$id"}]) { ... on ProductItem { id inventoryStatus } } }"""
        val anonymous = graphql(query)
        assertThat(anonymous["data"]["_entities"][0].isNull).isTrue()
        assertThat(errorMessage(anonymous)).startsWith("Exception was thrown while trying to resolve federated type")
        val ok = graphql(query, user = employee)
        assertThat(ok["data"]["_entities"][0]["id"].asString()).isEqualTo(id.toString())
    }

    // ---------------------------------------------------------------- events

    @Test
    fun `order created reserves the items and relays the order, then follows payment and shipment`() {
        val productVariantId = productVariant()
        createItems(productVariantId, 3)
        val orderId = UUID.randomUUID()
        val order = orderJson(orderId, listOf(productVariantId to 2))
        assertThat(orderCreated(order)).isEqualTo(200)
        val published = dapr.awaitPublished("inventory/product-item/reservation-succeeded")
            .filter { it.json["order"]["id"].asString() == orderId.toString() }
        assertThat(published).hasSize(1)
        assertThat(published[0].pubsubName).isEqualTo("pubsub")
        assertThat(published[0].json["order"]).isEqualTo(mapper.readTree(order))
        assertThat(published[0].body).contains("\"rejectionReason\":null").contains("\"paymentAuthorization\":{\"cvc\":123}")
        assertThat(inventoryCount(productVariantId)).isEqualTo(1)

        // a redelivered event reserves nothing more and publishes the result again
        assertThat(orderCreated(order)).isEqualTo(200)
        assertThat(inventoryCount(productVariantId)).isEqualTo(1)
        assertThat(dapr.awaitPublished("inventory/product-item/reservation-succeeded", 2)
            .filter { it.json["order"]["id"].asString() == orderId.toString() }).hasSize(2)

        val reserved = graphql("""{ productItems(filter: {productVariant: "$productVariantId", inventoryStatus: RESERVED}) { nodes { id orderId } } }""", user = employee)["data"]["productItems"]["nodes"].values()
            .map { UUID.fromString(it["id"].asString()) }
        assertThat(reserved).hasSize(2)
        val statuses = { reserved.map { item(it)["inventoryStatus"].asString() }.distinct() }

        assertThat(orderEvent("/subscription/payment/payment/payment-enabled", "payment/payment/payment-enabled", orderId)).isEqualTo(200)
        assertThat(statuses()).containsExactly("IN_FULFILLMENT")
        assertThat(shipmentEvent("/subscription/shipment/shipment/created", "shipment/shipment/created", orderId, "PENDING")).isEqualTo(200)
        assertThat(statuses()).containsExactly("SHIPPED")
        val statusUpdated = "/subscription/shipment/shipment/status-updated"
        shipmentEvent(statusUpdated, "shipment/shipment/status-updated", orderId, "IN_PROGRESS")
        assertThat(statuses()).containsExactly("SHIPPED")
        shipmentEvent(statusUpdated, "shipment/shipment/status-updated", orderId, "FAILED")
        assertThat(statuses()).containsExactly("LOST")
        shipmentEvent(statusUpdated, "shipment/shipment/status-updated", orderId, "DELIVERED")
        assertThat(statuses()).containsExactly("DELIVERED")
        // return shipments (no order) change nothing
        assertThat(shipmentEvent("/subscription/shipment/shipment/created", "shipment/shipment/created", null, "PENDING")).isEqualTo(200)

        // payment failed puts every item of the order back into storage
        assertThat(orderEvent("/subscription/payment/payment/payment-failed", "payment/payment/payment-failed", orderId)).isEqualTo(200)
        assertThat(reserved.map { item(it)["inventoryStatus"].asString() }).containsOnly("IN_STORAGE")
        assertThat(item(reserved[0])["orderId"].isNull).isTrue()
    }

    @Test
    fun `order created with insufficient stock publishes reservation-failed and releases the order`() {
        val productVariantId = productVariant()
        val short = productVariant()
        createItems(productVariantId, 2)
        val orderId = UUID.randomUUID()
        assertThat(orderCreated(orderJson(orderId, listOf(productVariantId to 1, short to 1)))).isEqualTo(200)
        val failed = dapr.awaitPublished("inventory/product-item/reservation-failed")
            .filter { it.json["order"]["id"].asString() == orderId.toString() }
        assertThat(failed).hasSize(1)
        assertThat(failed[0].json["failedProductVariantIds"].values().map { it.asString() }).containsExactly(short.toString())
        assertThat(inventoryCount(productVariantId)).isEqualTo(2)
        assertThat(dapr.published("inventory/product-item/reservation-succeeded")
            .filter { it.json["order"]["id"].asString() == orderId.toString() }).isEmpty()
    }

    @Test
    fun `a discount validation failure releases the items of the order`() {
        val productVariantId = productVariant()
        createItems(productVariantId, 1)
        val orderId = UUID.randomUUID()
        assertThat(orderCreated(orderJson(orderId, listOf(productVariantId to 1)))).isEqualTo(200)
        assertThat(inventoryCount(productVariantId)).isEqualTo(0)
        val status = deliverEvent(
            "/subscription/discount/order/validation-failed", "discount/order/validation-failed",
            """{"order":{"id":"$orderId"},"failingDiscountIds":[]}"""
        )
        assertThat(status).isEqualTo(200)
        assertThat(inventoryCount(productVariantId)).isEqualTo(1)
    }

    @Test
    fun `a failing publish of the reservation result rolls the reservation back, the redelivery reserves once`() {
        val productVariantId = productVariant()
        createItems(productVariantId, 2)
        val orderId = UUID.randomUUID()
        val order = orderJson(orderId, listOf(productVariantId to 1))
        dapr.publishStatus = 500
        try {
            assertThat(orderCreated(order)).isEqualTo(500)
        } finally {
            dapr.publishStatus = 204
        }
        assertThat(inventoryCount(productVariantId)).isEqualTo(2)
        assertThat(orderCreated(order)).isEqualTo(200)
        assertThat(inventoryCount(productVariantId)).isEqualTo(1)
        assertThat(orderCreated(order)).isEqualTo(200)
        assertThat(inventoryCount(productVariantId)).isEqualTo(1)
    }

    @Test
    fun `invalid events are rejected`() {
        assertThat(orderCreated("""{"id":"x","orderItems":[{"count":1}]}""")).isGreaterThanOrEqualTo(400)
        assertThat(deliverEvent("/subscription/payment/payment/payment-enabled", "payment/payment/payment-enabled", "{}")).isEqualTo(400)
        assertThat(deliverEvent("/subscription/catalog/product-variant/created", "catalog/product-variant/created", """{"id":"1"}""")).isEqualTo(400)
    }

    @Test
    fun `dapr subscriptions are generated for all topics`() {
        val body = webTestClient.get().uri("/dapr/subscribe").exchange().expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!
        val subscriptions = mapper.readTree(body).values().associate { it["topic"].asString() to it["route"].asString() }
        assertThat(subscriptions).containsOnlyKeys(
            "catalog/product-variant/created", "order/order/created", "payment/payment/payment-enabled",
            "payment/payment/payment-failed", "shipment/shipment/created", "shipment/shipment/status-updated",
            "discount/order/validation-failed"
        )
        subscriptions.forEach { (topic, route) -> assertThat(route).isEqualTo("/subscription/$topic") }
    }
}
