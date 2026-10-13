package org.misarch.`return`

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.misarch.common.testing.MisarchIntegrationTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.await
import org.springframework.r2dbc.core.awaitSingle
import tools.jackson.databind.JsonNode
import java.io.File
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Behaviour of the return service against a real Postgres (Testcontainers, original migration incl. triggers) and a
 * fake Dapr sidecar: order/shipment replication events, createReturn (validation, publication), queries,
 * connections, federation, authorization.
 */
class ReturnIntegrationTest : MisarchIntegrationTest() {

    @Autowired
    lateinit var databaseClient: DatabaseClient

    private val employee = authorizedUserHeader(UUID.randomUUID(), "employee")

    /** An order of [userId] with its items, a delivered shipment, and (optionally) the PVV replica row. */
    private inner class Scenario(
        val userId: UUID = UUID.randomUUID(),
        val itemCount: Int = 2,
        status: String = "DELIVERED",
        canBeReturnedForDays: Int? = null,
        insertPvv: Boolean = false,
    ) {
        val orderId: UUID = UUID.randomUUID()
        val pvvId: UUID = UUID.randomUUID()
        val shipmentId: UUID = UUID.randomUUID()
        val itemIds: List<UUID> = (1..itemCount).map { UUID.randomUUID() }
        val buyer = authorizedUserHeader(userId, "buyer")

        init {
            assertThat(deliverOrder(orderId, userId, itemIds.mapIndexed { i, id -> id to (100L * (i + 1)) }, pvvId)).isEqualTo(200)
            assertThat(deliverShipment(shipmentId, orderId, itemIds, "PENDING")).isEqualTo(200)
            if (status == "DELIVERED") {
                assertThat(deliverStatus(shipmentId, "DELIVERED")).isEqualTo(200)
            }
            if (insertPvv) {
                sql("INSERT INTO ProductVariantVersionEntity (id, canBeReturnedForDays) VALUES ('$pvvId', ${canBeReturnedForDays ?: "NULL"})")
            }
        }

        fun createReturn(ids: List<UUID> = itemIds, user: String? = buyer): JsonNode = graphql(
            """mutation(${'$'}input: CreateReturnInput!) { createReturn(input: ${'$'}input) { id reason refundedAmount createdAt order { id } returnedItems { totalCount nodes { id returnedWith { id } } } } }""",
            mapOf("input" to mapOf("orderItemIds" to ids.map { it.toString() }, "reason" to "Broken")),
            user
        )
    }

    private fun deliverOrder(orderId: UUID, userId: UUID, items: List<Pair<UUID, Long>>, pvvId: UUID): Int {
        // shape of the Rust order service's payload (camelCase, many fields the return service ignores)
        val itemsJson = items.joinToString(",") { (id, amount) ->
            """{"id":"$id","createdAt":"2024-05-01T10:00:00Z","productVariantId":"${UUID.randomUUID()}","productVariantVersionId":"$pvvId","taxRateVersionId":"${UUID.randomUUID()}","shoppingCartItemId":"${UUID.randomUUID()}","count":1,"compensatableAmount":$amount,"shipmentMethodId":"${UUID.randomUUID()}","discountIds":[]}"""
        }
        return deliverEvent(
            "/subscription/order/order/created", "order/order/created",
            """{"id":"$orderId","userId":"$userId","createdAt":"2024-05-01T10:00:00Z","orderStatus":"PLACED","placedAt":"2024-05-01T10:01:00Z","rejectionReason":null,"orderItems":[$itemsJson],"shipmentAddressId":"${UUID.randomUUID()}","invoiceAddressId":"${UUID.randomUUID()}","compensatableOrderAmount":300,"paymentInformationId":"${UUID.randomUUID()}","paymentAuthorization":null,"vatNumber":"DE1"}"""
        )
    }

    private fun deliverShipment(id: UUID, orderId: UUID?, itemIds: List<UUID>, status: String): Int = deliverEvent(
        "/subscription/shipment/shipment/created", "shipment/shipment/created",
        """{"id":"$id","orderId":${orderId?.let { "\"$it\"" } ?: "null"},"returnId":null,"status":"$status","orderItemIds":[${itemIds.joinToString(",") { "\"$it\"" }}],"shipmentMethodId":"${UUID.randomUUID()}","shipmentAddressId":"${UUID.randomUUID()}"}"""
    )

    private fun deliverStatus(id: UUID, status: String): Int = deliverEvent(
        "/subscription/shipment/shipment/status-updated", "shipment/shipment/status-updated",
        """{"id":"$id","status":"$status","orderId":null,"returnId":null,"orderItemIds":[]}"""
    )

    private fun sql(statement: String) = runBlocking { databaseClient.sql(statement).await() }

    private fun count(statement: String): Long = runBlocking {
        databaseClient.sql(statement).map { row -> (row.get(0) as Number).toLong() }.awaitSingle()
    }

    private fun isDelivered(shipmentId: UUID): Boolean =
        count("SELECT COUNT(*) FROM ShipmentEntity WHERE id = '$shipmentId' AND deliveredAt IS NOT NULL") == 1L

    private fun entities(representations: List<Map<String, String>>, selection: String, user: String?): JsonNode = graphql(
        """query(${'$'}r: [_Any!]!) { _entities(representations: ${'$'}r) { $selection } }""",
        mapOf("r" to representations), user
    )

    @Test
    fun `dapr subscribe equals the original's, the product variant version topic is not subscribed`() {
        val body = webTestClient.get().uri("/dapr/subscribe").exchange()
            .expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!
        val reference = File(System.getProperty("misarch.reference.dir"), "dapr-subscribe/return.json").readText()
        assertThat(mapper.readTree(body).toSet()).isEqualTo(mapper.readTree(reference).toSet())
        assertThat(mapper.readTree(body).size()).isEqualTo(3)
        val status = deliverEvent(
            "/subscription/catalog/product-variant-version/created", "catalog/product-variant-version/created",
            """{"id":"${UUID.randomUUID()}","canBeReturnedForDays":30}"""
        )
        assertThat(status).isEqualTo(404)
    }

    @Test
    fun `health endpoint is UP`() {
        webTestClient.get().uri("/health").exchange()
            .expectStatus().isOk
            .expectBody().json("""{"status":"UP"}""", org.springframework.test.json.JsonCompareMode.STRICT)
    }

    @Test
    fun `createReturn fails with the original's error because the PVV replica is never fed`() {
        val s = Scenario()
        val result = s.createReturn()
        assertThat(result.path("data").let { it.isMissingNode || it.isNull }).isTrue()
        assertThat(result["errors"][0]["message"].asString())
            .isEqualTo("Exception while fetching data (/createReturn) : Product variant version not found")
        assertThat(count("SELECT COUNT(*) FROM ReturnEntity WHERE orderId = '${s.orderId}'")).isEqualTo(0)
        assertThat(dapr.published).isEmpty()
    }

    @Test
    fun `createReturn succeeds with a PVV row, marks the items and publishes the original payload`() {
        val s = Scenario(insertPvv = true, canBeReturnedForDays = 30)
        val before = OffsetDateTime.now()
        val result = s.createReturn()
        assertThat(result["errors"]).isNull()
        val created = result["data"]["createReturn"]
        val returnId = created["id"].asString()
        assertThat(created["reason"].asString()).isEqualTo("Broken")
        assertThat(created["refundedAmount"].asInt()).isEqualTo(300)
        assertThat(created["order"]["id"].asString()).isEqualTo(s.orderId.toString())
        assertThat(created["returnedItems"]["totalCount"].asInt()).isEqualTo(2)
        assertThat(created["returnedItems"]["nodes"].toList().map { it["returnedWith"]["id"].asString() }).containsOnly(returnId)

        val events = dapr.awaitPublished("return/return/created")
        assertThat(events).hasSize(1)
        assertThat(events[0].pubsubName).isEqualTo("pubsub")
        val payload = events[0].json
        assertThat(payload.propertyNames().asSequence().toList())
            .containsExactly("id", "orderId", "orderItemIds", "reason", "refundedAmount", "createdAt")
        assertThat(payload["id"].asString()).isEqualTo(returnId)
        assertThat(payload["orderId"].asString()).isEqualTo(s.orderId.toString())
        assertThat(payload["orderItemIds"].toList().map { it.asString() }).containsExactlyElementsOf(s.itemIds.map { it.toString() })
        assertThat(payload["reason"].asString()).isEqualTo("Broken")
        assertThat(payload["refundedAmount"].isIntegralNumber).isTrue()
        assertThat(payload["refundedAmount"].asLong()).isEqualTo(300)
        val createdAt = OffsetDateTime.parse(payload["createdAt"].asString())
        assertThat(Duration.between(before, createdAt).abs()).isLessThan(Duration.ofMinutes(1))
        assertThat(payload["createdAt"].asString()).matches("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(\.\d{1,9})?(Z|[+-]\d\d:\d\d)""")

        // a second return of the same items
        val again = s.createReturn()
        assertThat(again["errors"][0]["message"].asString()).isEqualTo(
            "Exception while fetching data (/createReturn) : Order item ids ${s.itemIds} have already been returned"
        )

        // queries: by id, connections, federation
        val byOwner = graphql("""{ return(id: "$returnId") { id refundedAmount } }""", user = s.buyer)
        assertThat(byOwner["data"]["return"]["refundedAmount"].asInt()).isEqualTo(300)
        assertThat(graphql("""{ return(id: "$returnId") { id } }""", user = employee)["data"]["return"]["id"].asString()).isEqualTo(returnId)
        val foreign = graphql("""{ return(id: "$returnId") { id } }""", user = authorizedUserHeader(UUID.randomUUID(), "buyer"))
        assertThat(foreign["errors"][0]["message"].asString())
            .isEqualTo("Exception while fetching data (/return) : You are not authorized to view this return")
        assertThat(graphql("""{ return(id: "$returnId") { id } }""")["errors"][0]["message"].asString())
            .isEqualTo("Exception while fetching data (/return) : Unauthorized access")

        val own = graphql("{ returns { totalCount nodes { id } } }", user = s.buyer)["data"]["returns"]
        assertThat(own["totalCount"].asInt()).isEqualTo(1)
        assertThat(own["nodes"][0]["id"].asString()).isEqualTo(returnId)
        val all = graphql("{ returns { totalCount nodes { id } } }", user = employee)["data"]["returns"]
        assertThat(all["nodes"].toList().map { it["id"].asString() }).contains(returnId)

        val userReturns = entities(
            listOf(mapOf("__typename" to "User", "id" to s.userId.toString())),
            "... on User { returns { totalCount nodes { id } } }", s.buyer
        )["data"]["_entities"][0]["returns"]
        assertThat(userReturns["totalCount"].asInt()).isEqualTo(1)
        // a non-employee asking for another user's returns gets an empty connection, not an error
        val otherBuyer = entities(
            listOf(mapOf("__typename" to "User", "id" to s.userId.toString())),
            "... on User { returns { totalCount nodes { id } } }", authorizedUserHeader(UUID.randomUUID(), "buyer")
        )
        assertThat(otherBuyer["errors"]).isNull()
        assertThat(otherBuyer["data"]["_entities"][0]["returns"]["totalCount"].asInt()).isEqualTo(0)
        assertThat(entities(
            listOf(mapOf("__typename" to "User", "id" to s.userId.toString())),
            "... on User { returns { totalCount } }", employee
        )["data"]["_entities"][0]["returns"]["totalCount"].asInt()).isEqualTo(1)

        val items = entities(
            listOf(
                mapOf("__typename" to "OrderItem", "id" to s.itemIds[0].toString()),
                mapOf("__typename" to "OrderItem", "id" to UUID.randomUUID().toString()),
                mapOf("__typename" to "Order", "id" to s.orderId.toString()),
            ),
            "... on OrderItem { id returnedWith { id reason } } ... on Order { id }", null
        )
        assertThat(items["errors"]).isNull()
        assertThat(items["data"]["_entities"][0]["returnedWith"]["id"].asString()).isEqualTo(returnId)
        assertThat(items["data"]["_entities"][1]["returnedWith"].isNull).isTrue()
        assertThat(items["data"]["_entities"][2]["id"].asString()).isEqualTo(s.orderId.toString())

        // like the original, there is no entity resolver for Return
        val returnEntity = entities(listOf(mapOf("__typename" to "Return", "id" to returnId)), "... on Return { id }", employee)
        assertThat(returnEntity["data"]["_entities"][0].isNull).isTrue()
        assertThat(returnEntity["errors"]).isNotNull()
    }

    @Test
    fun `returnedItems paging and ordering`() {
        val s = Scenario(itemCount = 3, insertPvv = true)
        val returnId = s.createReturn()["data"]["createReturn"]["id"].asString()
        val sorted = s.itemIds.map { it.toString() }.sorted()
        fun items(args: String): JsonNode = graphql(
            """{ return(id: "$returnId") { returnedItems$args { totalCount hasNextPage nodes { id } } } }""", user = s.buyer
        )["data"]["return"]["returnedItems"]
        assertThat(items("")["nodes"].toList().map { it["id"].asString() }).containsExactlyElementsOf(sorted)
        assertThat(items("(orderBy: {direction: DESC})")["nodes"].toList().map { it["id"].asString() })
            .containsExactlyElementsOf(sorted.reversed())
        val page = items("(first: 1, skip: 1, orderBy: {field: ID, direction: ASC})")
        assertThat(page["totalCount"].asInt()).isEqualTo(3)
        assertThat(page["hasNextPage"].asBoolean()).isTrue()
        assertThat(page["nodes"].toList().map { it["id"].asString() }).containsExactly(sorted[1])
        assertThat(items("(first: 2, skip: 1)")["hasNextPage"].asBoolean()).isFalse()
    }

    @Test
    fun `returns connection paging and ordering, anonymous requests fail`() {
        val userId = UUID.randomUUID()
        val ids = (1..3).map {
            val s = Scenario(userId = userId, itemCount = 1, insertPvv = true)
            s.createReturn()["data"]["createReturn"]["id"].asString()
        }.sorted()
        val buyer = authorizedUserHeader(userId, "buyer")
        fun returns(args: String): JsonNode =
            graphql("{ returns$args { totalCount hasNextPage nodes { id } } }", user = buyer)["data"]["returns"]
        assertThat(returns("")["nodes"].toList().map { it["id"].asString() }).containsExactlyElementsOf(ids)
        assertThat(returns("(orderBy: {field: ID, direction: DESC})")["nodes"].toList().map { it["id"].asString() })
            .containsExactlyElementsOf(ids.reversed())
        val page = returns("(first: 1, skip: 1)")
        assertThat(page["nodes"].toList().map { it["id"].asString() }).containsExactly(ids[1])
        assertThat(page["totalCount"].asInt()).isEqualTo(3)
        assertThat(page["hasNextPage"].asBoolean()).isTrue()
        assertThat(returns("(first: 1, skip: 2)")["hasNextPage"].asBoolean()).isFalse()

        // without Authorized-User the connection's filter fails (NullPointerException in the original)
        val anonymous = graphql("{ returns { totalCount } }")
        assertThat(anonymous.path("data").let { it.isMissingNode || it.isNull }).isTrue()
        assertThat(anonymous["errors"][0]["message"].asString()).startsWith("Exception while fetching data (/returns/totalCount) : ")
    }

    @Test
    fun `createReturn validation errors`() {
        val s = Scenario(insertPvv = true)
        fun message(result: JsonNode) = result["errors"][0]["message"].asString().removePrefix("Exception while fetching data (/createReturn) : ")
        assertThat(message(s.createReturn(emptyList()))).isEqualTo("List is empty.")
        assertThat(message(s.createReturn(listOf(UUID.randomUUID())))).isEqualTo("List is empty.")
        val unknown = UUID.randomUUID()
        assertThat(message(s.createReturn(listOf(s.itemIds[0], unknown)))).isEqualTo("Order item ids [$unknown] do not exist")
        assertThat(message(s.createReturn(user = authorizedUserHeader(UUID.randomUUID(), "buyer"))))
            .isEqualTo("Only the original buyer can create returns")
        assertThat(message(s.createReturn(user = employee))).isEqualTo("Only the original buyer can create returns")
        assertThat(message(s.createReturn(user = null))).isEqualTo("Unauthorized access")

        val notDelivered = Scenario(userId = s.userId, status = "PENDING", insertPvv = true)
        assertThat(message(notDelivered.createReturn())).isEqualTo("At least one order item was not delivered")
        assertThat(message(s.createReturn(listOf(s.itemIds[0], notDelivered.itemIds[0]))))
            .isEqualTo("At least one order item was not delivered")
        val other = Scenario(userId = s.userId, insertPvv = true)
        assertThat(message(s.createReturn(listOf(s.itemIds[0], other.itemIds[0]))))
            .isEqualTo("All order items must belong to the same order")

        // order known, but no shipment yet: sentWithId is missing
        val orderId = UUID.randomUUID()
        val itemId = UUID.randomUUID()
        assertThat(deliverOrder(orderId, s.userId, listOf(itemId to 10L), UUID.randomUUID())).isEqualTo(200)
        assertThat(message(s.createReturn(listOf(itemId)))).isEqualTo("Missing data about order items, please try again later")

        val zeroDays = Scenario(userId = s.userId, insertPvv = true, canBeReturnedForDays = 0)
        assertThat(message(zeroDays.createReturn())).isEqualTo("Order item ${zeroDays.itemIds[0]} cannot be returned")
        val expired = Scenario(userId = s.userId, insertPvv = true, canBeReturnedForDays = 30)
        sql("UPDATE ShipmentEntity SET deliveredAt = now() - interval '40 days' WHERE id = '${expired.shipmentId}'")
        assertThat(message(expired.createReturn())).isEqualTo("Order item ${expired.itemIds[0]} can no longer be returned")
        assertThat(dapr.published).isEmpty()
    }

    @Test
    fun `failing publish fails createReturn and rolls back`() {
        val s = Scenario(insertPvv = true)
        dapr.publishStatus = 500
        val result = s.createReturn()
        assertThat(result["errors"]).isNotNull()
        dapr.publishStatus = 204
        assertThat(count("SELECT COUNT(*) FROM ReturnEntity WHERE orderId = '${s.orderId}'")).isEqualTo(0)
        assertThat(count("SELECT COUNT(*) FROM OrderItemEntity WHERE orderId = '${s.orderId}' AND returnedWithId IS NOT NULL")).isEqualTo(0)
        assertThat(s.createReturn()["errors"]).isNull()
    }

    @Test
    fun `order and shipment events replicate the data like the original`() {
        val userId = UUID.randomUUID()
        val orderId = UUID.randomUUID()
        val pvvId = UUID.randomUUID()
        val itemId = UUID.randomUUID()
        assertThat(deliverOrder(orderId, userId, listOf(itemId to 42L), pvvId)).isEqualTo(200)
        assertThat(count("SELECT COUNT(*) FROM OrderEntity WHERE id = '$orderId' AND userId = '$userId'")).isEqualTo(1)
        assertThat(count("SELECT COUNT(*) FROM OrderItemEntity WHERE id = '$itemId' AND compensatableAmount = 42 AND productVariantVersionId = '$pvvId' AND sentWithId IS NULL")).isEqualTo(1)
        // duplicate order: primary key violation -> 500 (Dapr redelivers)
        assertThat(deliverOrder(orderId, userId, listOf(itemId to 42L), pvvId)).isEqualTo(500)

        // shipment created with status DELIVERED: deliveredAt is set immediately
        val delivered = UUID.randomUUID()
        assertThat(deliverShipment(delivered, orderId, listOf(itemId), "DELIVERED")).isEqualTo(200)
        assertThat(isDelivered(delivered)).isTrue()
        assertThat(count("SELECT COUNT(*) FROM OrderItemEntity WHERE id = '$itemId' AND sentWithId = '$delivered'")).isEqualTo(1)

        // shipment for a return (no orderId): ignored
        val returnShipment = UUID.randomUUID()
        assertThat(deliverShipment(returnShipment, null, listOf(itemId), "PENDING")).isEqualTo(200)
        assertThat(count("SELECT COUNT(*) FROM ShipmentEntity WHERE id = '$returnShipment'")).isEqualTo(0)
        // its status updates fail forever (unknown shipment) when DELIVERED, others are no-ops
        assertThat(deliverStatus(returnShipment, "DELIVERED")).isEqualTo(500)
        assertThat(deliverStatus(returnShipment, "IN_PROGRESS")).isEqualTo(200)

        // status update to a non-delivered status changes nothing
        val pending = UUID.randomUUID()
        assertThat(deliverShipment(pending, orderId, listOf(itemId), "PENDING")).isEqualTo(200)
        assertThat(deliverStatus(pending, "FAILED")).isEqualTo(200)
        assertThat(isDelivered(pending)).isFalse()
        assertThat(deliverStatus(pending, "DELIVERED")).isEqualTo(200)
        assertThat(isDelivered(pending)).isTrue()

        // shipment before its order: the FK fails after the shipment row was written -> redelivery fails forever
        val early = UUID.randomUUID()
        val unknownOrder = UUID.randomUUID()
        assertThat(deliverShipment(early, unknownOrder, listOf(UUID.randomUUID()), "PENDING")).isEqualTo(500)
        assertThat(count("SELECT COUNT(*) FROM ShipmentEntity WHERE id = '$early'")).isEqualTo(1)
        assertThat(deliverShipment(early, unknownOrder, listOf(UUID.randomUUID()), "PENDING")).isEqualTo(500)

        // invalid payloads
        assertThat(deliverStatus(pending, "LOST")).isEqualTo(400)
        assertThat(deliverEvent("/subscription/order/order/created", "order/order/created", """{"id":"${UUID.randomUUID()}"}""")).isEqualTo(400)
        assertThat(dapr.published).isEmpty()
    }

    @Test
    fun `database triggers of the original migration are active`() {
        val a = Scenario(insertPvv = true)
        val b = Scenario(userId = a.userId, insertPvv = true)
        val returnId = a.createReturn()["data"]["createReturn"]["id"].asString()
        // an item of order b cannot be attached to a return of order a
        assertThatThrownBy {
            sql("UPDATE OrderItemEntity SET returnedWithId = '$returnId' WHERE id = '${b.itemIds[0]}'")
        }.hasMessageContaining("Returned order ID does not match the order ID of the return")
        // check_return_within_period: not delivered
        val pending = Scenario(userId = a.userId, status = "PENDING", insertPvv = true)
        assertThatThrownBy {
            sql("UPDATE OrderItemEntity SET returnedWithId = NULL WHERE id = '${pending.itemIds[0]}'")
        }.hasMessageContaining("Shipment has not been delivered yet.")
    }
}
