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
 * Behaviour of the return service against a real Postgres (Testcontainers, migration incl. triggers) and a
 * fake Dapr sidecar: order/shipment replication events, createReturn (validation, publication), queries,
 * connections, federation, authorization.
 */
class ReturnIntegrationTest : MisarchIntegrationTest() {

    @Autowired
    lateinit var databaseClient: DatabaseClient

    private val employee = authorizedUserHeader(UUID.randomUUID(), "employee")

    /**
     * An order of [userId] with its items, a (delivered) shipment, and (optionally) the PVV replica row - all fed by
     * the events of the real flow (order/created, shipment/created, status-updated, catalog PVV created).
     */
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
                assertThat(deliverStatus(shipmentId, "DELIVERED", orderId)).isEqualTo(200)
            }
            if (insertPvv) {
                assertThat(deliverPvv(pvvId, canBeReturnedForDays)).isEqualTo(200)
            }
        }

        fun createReturn(ids: List<UUID> = itemIds, user: String? = buyer): JsonNode = graphql(
            """mutation(${'$'}input: CreateReturnInput!) { createReturn(input: ${'$'}input) { id reason refundedAmount createdAt order { id } returnedItems { totalCount nodes { id returnedWith { id } } } } }""",
            mapOf("input" to mapOf("orderItemIds" to ids.map { it.toString() }, "reason" to "Broken")),
            user
        )
    }

    private fun deliverOrder(orderId: UUID, userId: UUID, items: List<Pair<UUID, Long>>, pvvId: UUID): Int {
        // shape of the order service's payload (camelCase, many fields the return service ignores)
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

    /** shape of shipment's `status-updated` (orderId omitted for return shipments, NON_NULL) */
    private fun deliverStatus(id: UUID, status: String, orderId: UUID? = UUID.randomUUID()): Int = deliverEvent(
        "/subscription/shipment/shipment/status-updated", "shipment/shipment/status-updated",
        """{"id":"$id",${orderId?.let { "\"orderId\":\"$it\"," } ?: ""}"orderItemIds":[],"status":"$status"}"""
    )

    /** the catalog's `product-variant-version/created` payload (reference/events; null properties omitted) */
    private fun deliverPvv(id: UUID, canBeReturnedForDays: Int?): Int = deliverEvent(
        "/subscription/catalog/product-variant-version/created", "catalog/product-variant-version/created",
        """{${canBeReturnedForDays?.let { "\"canBeReturnedForDays\":$it," } ?: ""}"createdAt":"2026-10-13T12:05:55.19028313Z","description":"calibration version","id":"$id","mediaIds":[],"name":"Product","productVariantId":"${UUID.randomUUID()}","retailPrice":24,"taxRateId":"${UUID.randomUUID()}","version":1,"weight":1.0}"""
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
    fun `dapr subscribe lists the reference subscriptions plus the product variant version topic, which is stored idempotently`() {
        val body = webTestClient.get().uri("/dapr/subscribe").exchange()
            .expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!
        val reference = File(System.getProperty("misarch.reference.dir"), "dapr-subscribe/return.json").readText()
        // catalog/product-variant-version/created is subscribed in addition to the reference list
        val pvvSubscription = mapper.readTree(
            """{"pubsubName":"pubsub","topic":"catalog/product-variant-version/created","route":"/subscription/catalog/product-variant-version/created","metadata":{}}"""
        )
        assertThat(mapper.readTree(body).toSet()).isEqualTo(mapper.readTree(reference).toSet() + setOf(pvvSubscription))
        assertThat(mapper.readTree(body).size()).isEqualTo(4)

        val pvvId = UUID.randomUUID()
        assertThat(deliverPvv(pvvId, 30)).isEqualTo(200)
        assertThat(count("SELECT COUNT(*) FROM ProductVariantVersionEntity WHERE id = '$pvvId' AND canBeReturnedForDays = 30")).isEqualTo(1)
        // redelivery: no duplicate key error
        assertThat(deliverPvv(pvvId, 30)).isEqualTo(200)
        // catalog omits a null canBeReturnedForDays (returnable indefinitely)
        val unlimited = UUID.randomUUID()
        assertThat(deliverPvv(unlimited, null)).isEqualTo(200)
        assertThat(count("SELECT COUNT(*) FROM ProductVariantVersionEntity WHERE id = '$unlimited' AND canBeReturnedForDays IS NULL")).isEqualTo(1)
        assertThat(dapr.published).isEmpty()
    }

    @Test
    fun `health endpoint is UP`() {
        webTestClient.get().uri("/health").exchange()
            .expectStatus().isOk
            .expectBody().json("""{"status":"UP"}""", org.springframework.test.json.JsonCompareMode.STRICT)
    }

    @Test
    fun `a buyer can return a delivered order item end to end`() {
        val userId = UUID.randomUUID()
        val orderId = UUID.randomUUID()
        val pvvId = UUID.randomUUID()
        val shipmentId = UUID.randomUUID()
        val itemId = UUID.randomUUID()
        val buyer = authorizedUserHeader(userId, "buyer")
        // the real flow: catalog PVV, order created, shipment created (PENDING), provider callback DELIVERED
        assertThat(deliverPvv(pvvId, 30)).isEqualTo(200)
        assertThat(deliverOrder(orderId, userId, listOf(itemId to 23L), pvvId)).isEqualTo(200)
        assertThat(deliverShipment(shipmentId, orderId, listOf(itemId), "PENDING")).isEqualTo(200)
        assertThat(deliverStatus(shipmentId, "DELIVERED", orderId)).isEqualTo(200)

        val result = graphql(
            """mutation { createReturn(input: {orderItemIds: ["$itemId"], reason: "too small"}) { id refundedAmount returnedItems { nodes { id } } } }""",
            user = buyer
        )
        assertThat(result["errors"]).isNull()
        val returnId = result["data"]["createReturn"]["id"].asString()
        assertThat(result["data"]["createReturn"]["refundedAmount"].asInt()).isEqualTo(23)
        val event = dapr.awaitPublished("return/return/created").single().json
        assertThat(event["id"].asString()).isEqualTo(returnId)
        assertThat(event["orderId"].asString()).isEqualTo(orderId.toString())
        assertThat(event["orderItemIds"].toList().map { it.asString() }).containsExactly(itemId.toString())

        // shipment then creates the return shipment; its status updates (no orderId) are acknowledged no-ops
        val returnShipment = UUID.randomUUID()
        assertThat(deliverShipment(returnShipment, null, listOf(itemId), "PENDING")).isEqualTo(200)
        assertThat(deliverStatus(returnShipment, "IN_PROGRESS", null)).isEqualTo(200)
        assertThat(deliverStatus(returnShipment, "DELIVERED", null)).isEqualTo(200)
        assertThat(count("SELECT COUNT(*) FROM ShipmentEntity WHERE id = '$returnShipment'")).isEqualTo(0)
    }

    @Test
    fun `createReturn with a replicated PVV marks the items and publishes the contract payload`() {
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
        assertThat(errorMessage(again)).isEqualTo(
            "Exception while fetching data (/createReturn) : Order item ids ${s.itemIds} have already been returned"
        )

        // queries: by id, connections, federation
        val byOwner = graphql("""{ return(id: "$returnId") { id refundedAmount } }""", user = s.buyer)
        assertThat(byOwner["data"]["return"]["refundedAmount"].asInt()).isEqualTo(300)
        assertThat(graphql("""{ return(id: "$returnId") { id } }""", user = employee)["data"]["return"]["id"].asString()).isEqualTo(returnId)
        val foreignBuyer = UUID.randomUUID()
        val foreign = graphql("""{ return(id: "$returnId") { id } }""", user = authorizedUserHeader(foreignBuyer, "buyer"))
        assertThat(errorMessage(foreign))
            .isEqualTo("Exception while fetching data (/return) : Unauthorized access: $foreignBuyer is not an employee or admin")
        assertThat(errorMessage(graphql("""{ return(id: "$returnId") { id } }""")))
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

        // Return is resolvable; an unknown id fails only its own entry
        val unknownReturn = UUID.randomUUID().toString()
        val returnEntity = entities(
            listOf(mapOf("__typename" to "Return", "id" to returnId), mapOf("__typename" to "Return", "id" to unknownReturn)),
            "... on Return { id reason refundedAmount order { id } }", null
        )
        assertThat(returnEntity["data"]["_entities"][0]["reason"].asString()).isEqualTo("Broken")
        assertThat(returnEntity["data"]["_entities"][0]["order"]["id"].asString()).isEqualTo(s.orderId.toString())
        assertThat(returnEntity["data"]["_entities"][1].isNull).isTrue()
        assertThat(returnEntity["errors"].toList().map { it["message"].asString() }).containsExactly(
            "Exception was thrown while trying to resolve federated type, representation={__typename=Return, id=$unknownReturn}"
        )
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

        // without Authorized-User: `Unauthorized access`
        val anonymous = graphql("{ returns { totalCount } }")
        assertThat(anonymous.path("data").let { it.isMissingNode || it.isNull }).isTrue()
        assertThat(errorMessage(anonymous)).isEqualTo("Exception while fetching data (/returns) : Unauthorized access")
        val anonymousUser = entities(
            listOf(mapOf("__typename" to "User", "id" to userId.toString())), "... on User { returns { totalCount } }", null
        )
        assertThat(errorMessage(anonymousUser))
            .isEqualTo("Exception while fetching data (/_entities[0]/returns) : Unauthorized access")
    }

    @Test
    fun `createReturn validation errors`() {
        val s = Scenario(insertPvv = true)
        assertThat(errorMessage(s.createReturn(emptyList()))).isEqualTo("Exception while fetching data (/createReturn) : At least one order item id must be provided")
        val unknownOnly = UUID.randomUUID()
        assertThat(errorMessage(s.createReturn(listOf(unknownOnly)))).isEqualTo("Exception while fetching data (/createReturn) : Order item ids [$unknownOnly] do not exist")
        val unknown = UUID.randomUUID()
        assertThat(errorMessage(s.createReturn(listOf(s.itemIds[0], unknown)))).isEqualTo("Exception while fetching data (/createReturn) : Order item ids [$unknown] do not exist")
        assertThat(errorMessage(s.createReturn(user = authorizedUserHeader(UUID.randomUUID(), "buyer"))))
            .isEqualTo("Exception while fetching data (/createReturn) : Only the original buyer can create returns")
        assertThat(errorMessage(s.createReturn(user = employee))).isEqualTo("Exception while fetching data (/createReturn) : Only the original buyer can create returns")
        assertThat(errorMessage(s.createReturn(user = null))).isEqualTo("Exception while fetching data (/createReturn) : Unauthorized access")

        val notDelivered = Scenario(userId = s.userId, status = "PENDING", insertPvv = true)
        assertThat(errorMessage(notDelivered.createReturn())).isEqualTo("Exception while fetching data (/createReturn) : At least one order item was not delivered")
        assertThat(errorMessage(s.createReturn(listOf(s.itemIds[0], notDelivered.itemIds[0]))))
            .isEqualTo("Exception while fetching data (/createReturn) : At least one order item was not delivered")
        val other = Scenario(userId = s.userId, insertPvv = true)
        assertThat(errorMessage(s.createReturn(listOf(s.itemIds[0], other.itemIds[0]))))
            .isEqualTo("Exception while fetching data (/createReturn) : All order items must belong to the same order")

        // order known, but no shipment yet: sentWithId is missing
        val orderId = UUID.randomUUID()
        val itemId = UUID.randomUUID()
        assertThat(deliverOrder(orderId, s.userId, listOf(itemId to 10L), UUID.randomUUID())).isEqualTo(200)
        assertThat(errorMessage(s.createReturn(listOf(itemId)))).isEqualTo("Exception while fetching data (/createReturn) : Missing data about order items, please try again later")

        val zeroDays = Scenario(userId = s.userId, insertPvv = true, canBeReturnedForDays = 0)
        assertThat(errorMessage(zeroDays.createReturn())).isEqualTo("Exception while fetching data (/createReturn) : Order item ${zeroDays.itemIds[0]} cannot be returned")
        val expired = Scenario(userId = s.userId, insertPvv = true, canBeReturnedForDays = 30)
        sql("UPDATE ShipmentEntity SET deliveredAt = now() - interval '40 days' WHERE id = '${expired.shipmentId}'")
        assertThat(errorMessage(expired.createReturn())).isEqualTo("Exception while fetching data (/createReturn) : Order item ${expired.itemIds[0]} can no longer be returned")
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
    fun `order and shipment events replicate the data, handlers are transactional and idempotent`() {
        val userId = UUID.randomUUID()
        val orderId = UUID.randomUUID()
        val pvvId = UUID.randomUUID()
        val itemId = UUID.randomUUID()
        assertThat(deliverOrder(orderId, userId, listOf(itemId to 42L), pvvId)).isEqualTo(200)
        assertThat(count("SELECT COUNT(*) FROM OrderEntity WHERE id = '$orderId' AND userId = '$userId'")).isEqualTo(1)
        assertThat(count("SELECT COUNT(*) FROM OrderItemEntity WHERE id = '$itemId' AND compensatableAmount = 42 AND productVariantVersionId = '$pvvId' AND sentWithId IS NULL")).isEqualTo(1)
        // a duplicate order (redelivery) is acknowledged
        assertThat(deliverOrder(orderId, userId, listOf(itemId to 42L), pvvId)).isEqualTo(200)
        assertThat(count("SELECT COUNT(*) FROM OrderItemEntity WHERE orderId = '$orderId'")).isEqualTo(1)

        // shipment created with status DELIVERED: deliveredAt is set immediately
        val delivered = UUID.randomUUID()
        assertThat(deliverShipment(delivered, orderId, listOf(itemId), "DELIVERED")).isEqualTo(200)
        assertThat(isDelivered(delivered)).isTrue()
        assertThat(count("SELECT COUNT(*) FROM OrderItemEntity WHERE id = '$itemId' AND sentWithId = '$delivered'")).isEqualTo(1)
        // redelivery of shipment/created: 200, deliveredAt kept
        assertThat(deliverShipment(delivered, orderId, listOf(itemId), "PENDING")).isEqualTo(200)
        assertThat(isDelivered(delivered)).isTrue()

        // shipment for a return (no orderId): ignored
        val returnShipment = UUID.randomUUID()
        assertThat(deliverShipment(returnShipment, null, listOf(itemId), "PENDING")).isEqualTo(200)
        assertThat(count("SELECT COUNT(*) FROM ShipmentEntity WHERE id = '$returnShipment'")).isEqualTo(0)
        // its status updates are acknowledged no-ops
        assertThat(deliverStatus(returnShipment, "DELIVERED", null)).isEqualTo(200)
        assertThat(deliverStatus(returnShipment, "IN_PROGRESS", null)).isEqualTo(200)
        assertThat(count("SELECT COUNT(*) FROM ShipmentEntity WHERE id = '$returnShipment'")).isEqualTo(0)

        // status update to a non-delivered status changes nothing
        val pending = UUID.randomUUID()
        assertThat(deliverShipment(pending, orderId, listOf(itemId), "PENDING")).isEqualTo(200)
        assertThat(deliverStatus(pending, "FAILED", orderId)).isEqualTo(200)
        assertThat(isDelivered(pending)).isFalse()
        assertThat(deliverStatus(pending, "DELIVERED", orderId)).isEqualTo(200)
        assertThat(isDelivered(pending)).isTrue()

        // DELIVERED processed before shipment/created (independent topics): recorded, kept by the later created event
        val overtaken = UUID.randomUUID()
        assertThat(deliverStatus(overtaken, "DELIVERED", orderId)).isEqualTo(200)
        assertThat(deliverShipment(overtaken, orderId, listOf(itemId), "PENDING")).isEqualTo(200)
        assertThat(isDelivered(overtaken)).isTrue()

        // shipment before its order: 500 and nothing written (rolled back), so the redelivery succeeds once the
        // order is known
        val early = UUID.randomUUID()
        val laterOrder = UUID.randomUUID()
        val laterItem = UUID.randomUUID()
        assertThat(deliverShipment(early, laterOrder, listOf(laterItem), "PENDING")).isEqualTo(500)
        assertThat(count("SELECT COUNT(*) FROM ShipmentEntity WHERE id = '$early'")).isEqualTo(0)
        assertThat(deliverOrder(laterOrder, userId, listOf(laterItem to 5L), pvvId)).isEqualTo(200)
        assertThat(deliverShipment(early, laterOrder, listOf(laterItem), "PENDING")).isEqualTo(200)
        assertThat(count("SELECT COUNT(*) FROM OrderItemEntity WHERE id = '$laterItem' AND sentWithId = '$early'")).isEqualTo(1)

        // invalid payloads
        assertThat(deliverStatus(pending, "LOST")).isEqualTo(400)
        assertThat(deliverEvent("/subscription/order/order/created", "order/order/created", """{"id":"${UUID.randomUUID()}"}""")).isEqualTo(400)
        assertThat(dapr.published).isEmpty()
    }
}
