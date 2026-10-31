package org.misarch.shoppingcart

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.misarch.common.testing.MisarchIntegrationTest
import org.misarch.common.testing.SqlStatementLog
import org.springframework.context.annotation.Import
import org.misarch.common.rust.RustUuid
import tools.jackson.databind.JsonNode
import java.io.File
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Behaviour of the shopping cart port against Postgres (Testcontainers) and the fake Dapr sidecar, compared with the
 * responses of the original (recorded from `ghcr.io/misarch/shoppingcart:main`, see docs/specs/shoppingcart.md).
 */
@Import(SqlStatementLog::class)
class ShoppingcartIntegrationTest : MisarchIntegrationTest() {

    override val graphqlPath: String = "/"

    // ---------------------------------------------------------------- helpers

    data class HttpResult(val status: Int, val contentType: String?, val body: String) {
        val json: JsonNode get() = mapper.readTree(body)
    }

    private fun post(path: String, body: String, contentType: String? = "application/json", user: String? = null): HttpResult {
        val result = webTestClient.post().uri(path)
            .apply { if (contentType != null) header("Content-Type", contentType) }
            .apply { if (user != null) header("Authorized-User", user) }
            .bodyValue(body)
            .exchange()
            .returnResult(String::class.java)
        val text = result.responseBody.collectList().block()!!.joinToString("")
        return HttpResult(result.status.value(), result.responseHeaders.contentType?.toString(), text)
    }

    private fun gql(query: String, variables: Map<String, Any?> = emptyMap(), user: String? = null): HttpResult =
        post("/", mapper.writeValueAsString(mapOf("query" to query, "variables" to variables)), user = user)

    private fun event(route: String, topic: String, data: String, contentType: String = "application/cloudevents+json"): HttpResult =
        post(
            route,
            """{"id":"${UUID.randomUUID()}","source":"test","type":"com.dapr.event.sent","specversion":"1.0",
               "datacontenttype":"application/json","topic":"$topic","pubsubname":"pubsub","data":$data}""",
            contentType
        )

    private fun newUser(): UUID = UUID.randomUUID().also {
        assertThat(event("/on-topic-event", "user/user/created", """{"id":"$it","username":"u"}""").body)
            .isEqualTo("""{"status":0}""")
    }

    private fun newProductVariant(): UUID = UUID.randomUUID().also {
        assertThat(event("/on-topic-event", "catalog/product-variant/created", """{"id":"$it","productId":"$it"}""").status)
            .isEqualTo(200)
    }

    private fun buyer(id: UUID) = authorizedUserHeader(id, "buyer")

    private fun error(message: String, path: List<Any>?, line: Int, column: Int): String =
        mapper.writeValueAsString(
            linkedMapOf(
                "data" to null,
                "errors" to listOf(
                    linkedMapOf<String, Any>("message" to message, "locations" to listOf(mapOf("line" to line, "column" to column)))
                        .apply { if (path != null) put("path", path) }
                )
            )
        )

    private fun createItem(user: UUID, productVariant: UUID, count: Int = 1, header: String? = buyer(user)): HttpResult =
        gql(CREATE, mapOf("i" to mapOf("id" to "$user", "shoppingCartItem" to mapOf("count" to count, "productVariantId" to "$productVariant"))), header)

    private fun cart(user: UUID, args: String = "", fields: String = ITEM_FIELDS, header: String? = null): HttpResult =
        gql(CART.format(args, fields), mapOf("r" to listOf(mapOf("__typename" to "User", "id" to "$user"))), header)

    private fun JsonNode.items(): List<JsonNode> = iterator().asSequence().toList()

    private fun lastUpdatedAt(user: UUID): String = cart(user).json["data"]["_entities"][0]["shoppingcart"]["lastUpdatedAt"].asString()

    /** Waits until the wall clock (ms) has advanced, so that a new `now` differs from earlier timestamps. */
    private fun nextMillisecond() = Thread.sleep(5)

    private val timestamp = Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(\.\d{3})?\+00:00""")

    // ---------------------------------------------------------------- HTTP surface

    @Test
    fun `dapr subscribe is byte-identical to the original`() {
        val body = webTestClient.get().uri("/dapr/subscribe").exchange().expectStatus().isOk
            .expectHeader().contentType("application/json")
            .expectBody(String::class.java).returnResult().responseBody!!
        assertThat(body).isEqualTo(File("../../reference/dapr-subscribe/shoppingcart.json").readText().trim())
    }

    @Test
    fun `health answers 200 with an empty body`() {
        val result = webTestClient.get().uri("/health").exchange().expectStatus().isOk.expectBody().returnResult()
        assertThat(result.responseBody).isNull()
    }

    @Test
    fun `GET root serves GraphiQL, POST root executes GraphQL with the original content type`() {
        val html = webTestClient.get().uri("/").exchange().expectStatus().isOk
            .expectHeader().contentType("text/html;charset=utf-8")
            .expectBody(String::class.java).returnResult().responseBody!!
        assertThat(html).contains("<title>GraphiQL IDE</title>").contains("createUrl('/')")
        val result = gql("{ __typename }")
        assertThat(result.contentType).isEqualTo("application/graphql-response+json")
        assertThat(result.body).isEqualTo("""{"data":{"__typename":"Query"}}""")
    }

    @Test
    fun `batched requests are rejected, request-level errors have data null and no extensions`() {
        assertThat(post("/", """[{"query":"{ __typename }"}]""").status).isEqualTo(400)
        val unknownField = gql("query { foo }").json
        assertThat(unknownField["data"].isNull).isTrue()
        assertThat(unknownField["errors"][0].has("extensions")).isFalse()
        assertThat(unknownField["errors"][0]["locations"][0]["column"].asInt()).isEqualTo(9)
    }

    // ---------------------------------------------------------------- events

    @Test
    fun `topic events - success, idempotent duplicates, unknown topics and malformed bodies`() {
        val user = UUID.randomUUID()
        event("/on-topic-event", "user/user/created", """{"id":"$user"}""").let {
            assertThat(it.status).isEqualTo(200)
            assertThat(it.contentType).isEqualTo("application/json")
            assertThat(it.body).isEqualTo("""{"status":0}""")
        }
        // fix C3: a redelivered event succeeds and changes nothing (the original answered 500 forever)
        val variant = newProductVariant()
        assertThat(createItem(user, variant).json["errors"]).isNull()
        val before = cart(user).json
        nextMillisecond()
        event("/on-topic-event", "user/user/created", """{"id":"$user"}""").let {
            assertThat(it.status).isEqualTo(200)
            assertThat(it.body).isEqualTo("""{"status":0}""")
        }
        assertThat(cart(user).json).isEqualTo(before)
        assertThat(event("/on-topic-event", "catalog/product-variant/created", """{"id":"$variant"}""").body).isEqualTo("""{"status":0}""")
        assertThat(event("/on-topic-event", "foo/bar", """{"id":"${UUID.randomUUID()}"}""").status).isEqualTo(500)
        assertThat(event("/on-topic-event", "user/user/created", """{"x":1}""").status).isEqualTo(422)
        assertThat(event("/on-topic-event", "user/user/created", """{"id":"nope"}""").status).isEqualTo(422)
        assertThat(event("/on-topic-event", "user/user/created", """null""").status).isEqualTo(422)
        assertThat(event("/on-topic-event", "user/user/created", """{"id":"$user"}""", "text/plain").status).isEqualTo(415)
        assertThat(post("/on-topic-event", """{"topic":"user/user/created","data":{"id":"${UUID.randomUUID()}"}}""", null).status)
            .isEqualTo(415)
        assertThat(post("/on-topic-event", "{", "application/json").status).isEqualTo(400)
        assertThat(post("/on-topic-event", """{"data":{"id":"${UUID.randomUUID()}"}}""", "application/json").status).isEqualTo(422)
        // a plain application/json body with only topic + data works (the original reads nothing else)
        assertThat(post("/on-topic-event", """{"topic":"user/user/created","data":{"id":"${UUID.randomUUID()}"}}""").body)
            .isEqualTo("""{"status":0}""")
        assertThat(dapr.published).isEmpty()
    }

    @Test
    fun `order created removes the ordered items of that user only, idempotently`() {
        val user = newUser()
        val other = newUser()
        val variant = newProductVariant()
        val item1 = createItem(user, variant).json["data"]["createShoppingcartItem"]["id"].asString()
        val item2 = createItem(user, newProductVariant()).json["data"]["createShoppingcartItem"]["id"].asString()
        val otherItem = createItem(other, variant).json["data"]["createShoppingcartItem"]["id"].asString()
        val lastUpdatedAt = lastUpdatedAt(user)
        val otherLastUpdatedAt = lastUpdatedAt(other)
        nextMillisecond()

        val order = """{"id":"${UUID.randomUUID()}","userId":"$user","orderStatus":"PLACED","orderItems":[
            {"id":"${UUID.randomUUID()}","shoppingCartItemId":"$item1","count":1,"compensatableAmount":20,"discountIds":[]},
            {"id":"${UUID.randomUUID()}","shoppingCartItemId":"$otherItem","count":1},
            {"id":"${UUID.randomUUID()}","shoppingCartItemId":"${UUID.randomUUID()}","count":3}]}"""
        event("/on-order-creation-event", "order/order/created", order).let {
            assertThat(it.status).isEqualTo(200)
            assertThat(it.body).isEqualTo("""{"status":0}""")
        }
        // fix C4: removing items updates lastUpdatedAt; a redelivery removes nothing and changes nothing
        val afterOrder = lastUpdatedAt(user)
        assertThat(OffsetDateTime.parse(afterOrder)).isAfter(OffsetDateTime.parse(lastUpdatedAt))
        nextMillisecond()
        assertThat(event("/on-order-creation-event", "order/order/created", order).status).isEqualTo(200)
        assertThat(lastUpdatedAt(user)).isEqualTo(afterOrder)
        assertThat(event("/on-order-creation-event", "order/order/created",
            """{"id":"${UUID.randomUUID()}","userId":"${UUID.randomUUID()}","orderItems":[]}""").status).isEqualTo(200)
        assertThat(event("/on-order-creation-event", "order/order/foo",
            """{"id":"${UUID.randomUUID()}","userId":"$user","orderItems":[]}""").status).isEqualTo(500)
        assertThat(event("/on-order-creation-event", "order/order/created",
            """{"id":"${UUID.randomUUID()}","userId":"$user","orderItems":[{"shoppingCartItemId":"$item2","count":-1}]}""").status)
            .isEqualTo(422)
        assertThat(event("/on-order-creation-event", "order/order/created",
            """{"id":"${UUID.randomUUID()}","userId":"$user","orderItems":[{"shoppingCartItemId":"$item2"}]}""").status)
            .isEqualTo(422)
        assertThat(event("/on-order-creation-event", "order/order/created",
            """{"id":"${UUID.randomUUID()}","orderItems":[]}""").status).isEqualTo(422)

        val userCart = cart(user).json["data"]["_entities"][0]["shoppingcart"]
        assertThat(userCart["shoppingcartItems"]["nodes"].items().map { it["id"].asString() }).containsExactly(item2)
        assertThat(userCart["lastUpdatedAt"].asString()).isEqualTo(afterOrder)
        assertThat(cart(other).json["data"]["_entities"][0]["shoppingcart"]["shoppingcartItems"]["totalCount"].asInt())
            .isEqualTo(1)
        assertThat(lastUpdatedAt(other)).isEqualTo(otherLastUpdatedAt)
    }

    // ---------------------------------------------------------------- createShoppingcartItem

    @Test
    fun `createShoppingcartItem - authorization, validation order and messages`() {
        val user = newUser()
        val variant = newProductVariant()
        val unknown = UUID.randomUUID()
        val other = UUID.randomUUID()
        assertThat(createItem(user, variant, header = null).body).isEqualTo(
            error("Authentication failed. Authorized-User header is not set or could not be parsed.", listOf("createShoppingcartItem"), 1, 46)
        )
        assertThat(createItem(user, variant, header = """{"id":"$user","roles":["BUYER"]}""").json["errors"][0]["message"].asString())
            .isEqualTo("Authentication failed. Authorized-User header is not set or could not be parsed.")
        assertThat(createItem(user, variant, header = "not json").json["errors"][0]["message"].asString())
            .isEqualTo("Authentication failed. Authorized-User header is not set or could not be parsed.")
        assertThat(createItem(user, variant, header = buyer(other)).body).isEqualTo(
            error("Authentication failed for user of UUID: `$other`. Operation not permitted.", listOf("createShoppingcartItem"), 1, 46)
        )
        // user is checked before the product variant
        assertThat(createItem(unknown, UUID.randomUUID(), header = authorizedUserHeader(other, "employee")).body).isEqualTo(
            error("misarch_shoppingcart::graphql::model::user::User with UUID: `$unknown` not found.", listOf("createShoppingcartItem"), 1, 46)
        )
        assertThat(createItem(user, unknown).body).isEqualTo(
            error("Product variant with the UUID: `$unknown` is not present in the system.", listOf("createShoppingcartItem"), 1, 46)
        )
        // negative count: rejected while parsing, before authorization
        assertThat(createItem(user, variant, count = -1, header = null).body).isEqualTo(
            error(
                "Failed to parse \"Int\": Invalid number (occurred while parsing \"ShoppingCartItemInput\") " +
                    "(occurred while parsing \"CreateShoppingCartItemInput\")",
                listOf("createShoppingcartItem"), 1, 76
            )
        )
        assertThat(cart(user).json["data"]["_entities"][0]["shoppingcart"]["shoppingcartItems"]["totalCount"].asInt()).isZero()
    }

    @Test
    fun `createShoppingcartItem reuses the item of the same variant (fix C1), count 0 is accepted, lastUpdatedAt set`() {
        val user = newUser()
        val variant = newProductVariant()
        val before = lastUpdatedAt(user)
        nextMillisecond()
        val first = createItem(user, variant, 1)
        assertThat(first.contentType).isEqualTo("application/graphql-response+json")
        val firstItem = first.json["data"]["createShoppingcartItem"]
        assertThat(firstItem["count"].asInt()).isEqualTo(1)
        assertThat(firstItem["addedAt"].asString()).matches(timestamp.pattern)
        assertThat(firstItem["productVariant"]["id"].asString()).isEqualTo("$variant")
        // fix C4: adding an item updates lastUpdatedAt (= the item's addedAt)
        assertThat(lastUpdatedAt(user)).isEqualTo(firstItem["addedAt"].asString()).isNotEqualTo(before)
        nextMillisecond()
        // the same variant again: the existing item is returned unchanged, nothing is added
        val second = createItem(user, variant, 2).json["data"]["createShoppingcartItem"]
        assertThat(second).isEqualTo(firstItem)
        assertThat(lastUpdatedAt(user)).isEqualTo(firstItem["addedAt"].asString())
        val zero = createItem(user, newProductVariant(), 0, header = authorizedUserHeader(UUID.randomUUID(), "admin"))
            .json["data"]["createShoppingcartItem"]
        assertThat(zero["count"].asInt()).isZero()

        val shoppingcart = cart(user).json["data"]["_entities"][0]["shoppingcart"]
        assertThat(shoppingcart["lastUpdatedAt"].asString()).isEqualTo(zero["addedAt"].asString())
        assertThat(shoppingcart["shoppingcartItems"]["totalCount"].asInt()).isEqualTo(2)
        assertThat(shoppingcart["shoppingcartItems"]["nodes"].items().map { it["count"].asInt() }.sorted()).containsExactly(0, 1)

        // concurrent creations of one variant for one user create one item
        val concurrentVariant = newProductVariant()
        val ids = (1..4).map { java.util.concurrent.CompletableFuture.supplyAsync { createItem(user, concurrentVariant).json } }
            .map { it.get()["data"]["createShoppingcartItem"]["id"].asString() }
        assertThat(ids.toSet()).hasSize(1)
        assertThat(cart(user).json["data"]["_entities"][0]["shoppingcart"]["shoppingcartItems"]["totalCount"].asInt()).isEqualTo(3)
        // literal arguments in all UUID formats of the Rust uuid crate
        val literal = gql(
            """mutation { createShoppingcartItem(input: {id: "{${user.toString().uppercase()}}", shoppingCartItem: {count: 3, productVariantId: "urn:uuid:$variant"}}) { productVariant { id } } }""",
            user = buyer(user)
        )
        assertThat(literal.json["data"]["createShoppingcartItem"]["productVariant"]["id"].asString()).isEqualTo("$variant")
        val simple = gql(
            """mutation { createShoppingcartItem(input: {id: "${user.toString().replace("-", "")}", shoppingCartItem: {count: 4, productVariantId: "${newProductVariant()}"}}) { count } }""",
            user = buyer(user)
        )
        assertThat(simple.json["data"]["createShoppingcartItem"]["count"].asInt()).isEqualTo(4)
        assertThat(gql("""mutation { createShoppingcartItem(input: {id: "1-2-3-4-5", shoppingCartItem: {count: 4, productVariantId: "$variant"}}) { count } }""",
            user = buyer(user)).json["errors"]).isNotNull
    }

    // ---------------------------------------------------------------- _entities and the order contract

    @Test
    fun `entities User - no authorization, items sorted by unsigned id, pagination`() {
        val user = newUser()
        val ids = (1..6).map { createItem(user, newProductVariant(), it).json["data"]["createShoppingcartItem"]["id"].asString() }
        val sorted = ids.map(UUID::fromString).sortedWith(RustUuid.ORDER).map { it.toString() }
        assertThat(sorted).isEqualTo(ids.sorted()) // unsigned byte order = order of the lowercase hex strings

        cart(user).json["data"]["_entities"][0].let {
            assertThat(it["__typename"].asString()).isEqualTo("User")
            assertThat(it["id"].asString()).isEqualTo("$user")
            assertThat(it["shoppingcart"]["lastUpdatedAt"].asString()).matches(timestamp.pattern)
            val connection = it["shoppingcart"]["shoppingcartItems"]
            assertThat(connection["totalCount"].asInt()).isEqualTo(6)
            assertThat(connection["hasNextPage"].asBoolean()).isFalse()
            assertThat(connection["nodes"].items().map { n -> n["id"].asString() }).isEqualTo(sorted)
        }
        cart(user, "(first: 2, skip: 1, orderBy: {direction: DESC})").json["data"]["_entities"][0]["shoppingcart"]["shoppingcartItems"].let {
            assertThat(it["nodes"].items().map { n -> n["id"].asString() }).isEqualTo(sorted.reversed().subList(1, 3))
            assertThat(it["hasNextPage"].asBoolean()).isTrue()
        }
        cart(user, "(first: 0)").json["data"]["_entities"][0]["shoppingcart"]["shoppingcartItems"].let {
            assertThat(it["nodes"].size()).isZero()
            assertThat(it["hasNextPage"].asBoolean()).isTrue()
        }
        cart(user, "(first: 2, skip: 4)").json["data"]["_entities"][0]["shoppingcart"]["shoppingcartItems"].let {
            assertThat(it["nodes"].items().map { n -> n["id"].asString() }).isEqualTo(sorted.subList(4, 6))
            assertThat(it["hasNextPage"].asBoolean()).isFalse()
            assertThat(it["totalCount"].asInt()).isEqualTo(6)
        }
        cart(user, "(first: 2, skip: 3)").json["data"]["_entities"][0]["shoppingcart"]["shoppingcartItems"].let {
            assertThat(it["nodes"].items().map { n -> n["id"].asString() }).isEqualTo(sorted.subList(3, 5))
            assertThat(it["hasNextPage"].asBoolean()).isTrue()
        }
        cart(user, "(skip: 10, orderBy: {field: ID})").json["data"]["_entities"][0]["shoppingcart"]["shoppingcartItems"].let {
            assertThat(it["nodes"].size()).isZero()
            assertThat(it["hasNextPage"].asBoolean()).isFalse()
        }
        // negative pagination argument: field error, whole data null, path without list index
        assertThat(cart(user, "(first: -1)", "id").body).isEqualTo(
            error("Failed to parse \"Int\": Invalid number", listOf("_entities", "shoppingcart", "shoppingcartItems"), 1, 138)
        )
    }

    @Test
    fun `the user entity reads only its UserEntity row, the items are read only when selected`() {
        val user = newUser()
        repeat(3) { createItem(user, newProductVariant()) }
        val representations = mapOf("r" to listOf(mapOf("__typename" to "User", "id" to "$user")))
        fun record(query: String): List<String> = SqlStatementLog.record {
            assertThat(gql(query, representations).json["errors"]).isNull()
        }
        fun itemStatements(statements: List<String>) = statements.filter { "ShoppingCartItemEntity" in it }

        val cartOnly = record("query(\$r: [_Any!]!) { _entities(representations: \$r) { ... on User { id shoppingcart { lastUpdatedAt } } } }")
        assertThat(cartOnly).hasSize(1)
        assertThat(cartOnly.single()).contains("FROM UserEntity")

        // order's createOrder invocation (S20): the user row and one page of the items (no count, no probe)
        val nodesOnly = SqlStatementLog.record {
            assertThat(gql(ORDER_QUERY, mapOf("representations" to representations["r"])).json["errors"]).isNull()
        }
        assertThat(nodesOnly).hasSize(2)
        assertThat(itemStatements(nodesOnly)).singleElement().satisfies({ assertThat(it).startsWith("SELECT t.* FROM ShoppingCartItemEntity t") })

        val all = record(CART.format("(first: 2)", "id"))
        assertThat(itemStatements(all)).hasSize(3)
        assertThat(itemStatements(all)).anyMatch { it.startsWith("SELECT COUNT(t.id)") }.anyMatch { it.startsWith("SELECT 1 FROM") }
    }

    @Test
    fun `entities - errors are all-or-nothing with the original messages`() {
        val user = newUser()
        val unknown = UUID.randomUUID()
        val query = "query(\$r: [_Any!]!) { _entities(representations: \$r) { __typename } }"
        fun entities(vararg representations: Any) = gql(query, mapOf("r" to representations.toList()))

        assertThat(entities(mapOf("__typename" to "User", "id" to "$unknown")).body).isEqualTo(
            error("misarch_shoppingcart::graphql::model::user::User with UUID: `$unknown` not found.", listOf("_entities"), 1, 23)
        )
        assertThat(entities(mapOf("__typename" to "User", "id" to "$user"), mapOf("__typename" to "User", "id" to "$unknown")).body)
            .isEqualTo(error("misarch_shoppingcart::graphql::model::user::User with UUID: `$unknown` not found.", listOf("_entities"), 1, 23))
        // find_entity errors without path
        assertThat(entities(mapOf("__typename" to "ProductVariant", "id" to "$user")).body)
            .isEqualTo(error("Entity not found.", null, 1, 23))
        assertThat(entities(mapOf("__typename" to "User", "id" to "nope")).body)
            .isEqualTo(error("Entity not found.", null, 1, 23))
        assertThat(entities("not an object").body).isEqualTo(error("Entity not found.", null, 1, 23))
        assertThat(entities(mapOf("id" to "$user")).body)
            .isEqualTo(error("\"__typename\" must be an existing string.", null, 1, 23))
        assertThat(entities(mapOf("__typename" to "ShoppingCartItem", "id" to "$unknown")).json["errors"][0]["message"].asString())
            .isEqualTo("ShoppingCartItem of UUID: `$unknown` not found.")
        assertThat(entities(mapOf("__typename" to "User", "id" to "$user")).body)
            .isEqualTo("""{"data":{"_entities":[{"__typename":"User"}]}}""")
    }

    @Test
    fun `entities ShoppingCartItem - no authorization`() {
        val user = newUser()
        val variant = newProductVariant()
        val item = createItem(user, variant, 7).json["data"]["createShoppingcartItem"]
        val result = gql(
            "query(\$r: [_Any!]!) { _entities(representations: \$r) { __typename ... on ShoppingCartItem { $ITEM_FIELDS } } }",
            mapOf("r" to listOf(mapOf("__typename" to "ShoppingCartItem", "id" to item["id"].asString())))
        ).json["data"]["_entities"][0]
        assertThat(result["__typename"].asString()).isEqualTo("ShoppingCartItem")
        assertThat(result["id"]).isEqualTo(item["id"])
        assertThat(result["count"].asInt()).isEqualTo(7)
        assertThat(result["addedAt"]).isEqualTo(item["addedAt"])
        assertThat(result["productVariant"]["id"].asString()).isEqualTo("$variant")
    }

    @Test
    fun `order service invocation contract - exact request of createOrder`() {
        val user = newUser()
        val variants = (1..3).map { newProductVariant() }
        val items = variants.mapIndexed { index, variant ->
            createItem(user, variant, index + 1).json["data"]["createShoppingcartItem"]["id"].asString() to variant
        }
        // body as graphql_client serializes it (QueryBody: variables, query, operationName) + forwarded header
        val body = mapper.writeValueAsString(
            linkedMapOf(
                "variables" to mapOf("representations" to listOf(mapOf("__typename" to "User", "id" to "$user"))),
                "query" to ORDER_QUERY,
                "operationName" to "GetShoppingCartProductVariantIdsAndCounts"
            )
        )
        val result = post("/", body, user = """{"id":"$user","roles":["buyer"]}""")
        assertThat(result.status).isEqualTo(200)
        val expectedNodes = items.sortedBy { it.first }.map { (id, variant) ->
            """{"id":"$id","productVariant":{"id":"$variant"},"count":${items.indexOfFirst { it.first == id } + 1}}"""
        }
        assertThat(result.body).isEqualTo(
            """{"data":{"_entities":[{"__typename":"User","shoppingcart":{"shoppingcartItems":{"nodes":[${expectedNodes.joinToString(",")}]}}}]}}"""
        )
        // unknown user (not replicated): data null -> order fails with "Response data ... is empty."
        val unknown = post("/", body.replace("$user", "${UUID.randomUUID()}"))
        assertThat(unknown.json["data"].isNull).isTrue()
    }

    // ---------------------------------------------------------------- shoppingcartItem / update / delete

    @Test
    fun `shoppingcartItem, updateShoppingcartItem and deleteShoppingcartItem check existence before authorization`() {
        val user = newUser()
        val other = UUID.randomUUID()
        val unknown = UUID.randomUUID()
        val variant = newProductVariant()
        val item = createItem(user, variant).json["data"]["createShoppingcartItem"]
        val itemId = item["id"].asString()
        val lastUpdatedAt = lastUpdatedAt(user)
        nextMillisecond()

        assertThat(gql(ITEM_QUERY, mapOf("id" to "$unknown")).body)
            .isEqualTo(error("ShoppingCartItem of UUID: `$unknown` not found.", listOf("shoppingcartItem"), 1, 21))
        assertThat(gql(ITEM_QUERY, mapOf("id" to itemId)).body).isEqualTo(
            error("Authentication failed. Authorized-User header is not set or could not be parsed.", listOf("shoppingcartItem"), 1, 21)
        )
        assertThat(gql(ITEM_QUERY, mapOf("id" to itemId), buyer(other)).body).isEqualTo(
            error("Authentication failed for user of UUID: `$other`. Operation not permitted.", listOf("shoppingcartItem"), 1, 21)
        )
        assertThat(gql(ITEM_QUERY, mapOf("id" to itemId), buyer(user)).json["data"]["shoppingcartItem"]).isEqualTo(item)
        assertThat(gql(ITEM_QUERY, mapOf("id" to itemId), authorizedUserHeader(other, "employee")).json["data"]["shoppingcartItem"])
            .isEqualTo(item)

        assertThat(gql(UPDATE_ITEM, mapOf("i" to mapOf("id" to "$unknown", "count" to 5))).body)
            .isEqualTo(error("ShoppingCartItem of UUID: `$unknown` not found.", listOf("updateShoppingcartItem"), 1, 46))
        assertThat(gql(UPDATE_ITEM, mapOf("i" to mapOf("id" to itemId, "count" to 5)), buyer(other)).json["errors"][0]["message"].asString())
            .isEqualTo("Authentication failed for user of UUID: `$other`. Operation not permitted.")
        assertThat(gql(UPDATE_ITEM, mapOf("i" to mapOf("id" to "$unknown", "count" to -1))).body).isEqualTo(
            error("Failed to parse \"Int\": Invalid number (occurred while parsing \"UpdateShoppingCartItemInput\")", listOf("updateShoppingcartItem"), 1, 76)
        )
        val updated = gql(UPDATE_ITEM, mapOf("i" to mapOf("id" to itemId, "count" to 5)), buyer(user)).json["data"]["updateShoppingcartItem"]
        assertThat(updated["count"].asInt()).isEqualTo(5)
        assertThat(updated["addedAt"]).isEqualTo(item["addedAt"])
        // fix C4: updating an item updates lastUpdatedAt (failed attempts do not)
        val afterUpdate = lastUpdatedAt(user)
        assertThat(OffsetDateTime.parse(afterUpdate)).isAfter(OffsetDateTime.parse(lastUpdatedAt))
        nextMillisecond()

        assertThat(gql(DELETE, mapOf("id" to "$unknown"), buyer(user)).body)
            .isEqualTo(error("ShoppingCartItem of UUID: `$unknown` not found.", listOf("deleteShoppingcartItem"), 1, 24))
        assertThat(gql(DELETE, mapOf("id" to itemId), buyer(other)).json["errors"][0]["message"].asString())
            .isEqualTo("Authentication failed for user of UUID: `$other`. Operation not permitted.")
        assertThat(gql(DELETE, mapOf("id" to itemId), buyer(user)).body).isEqualTo("""{"data":{"deleteShoppingcartItem":true}}""")
        assertThat(gql(DELETE, mapOf("id" to itemId), buyer(user)).json["errors"][0]["message"].asString())
            .isEqualTo("ShoppingCartItem of UUID: `$itemId` not found.")
        // fix C4: deleting an item updates lastUpdatedAt
        assertThat(OffsetDateTime.parse(lastUpdatedAt(user))).isAfter(OffsetDateTime.parse(afterUpdate))
    }

    // ---------------------------------------------------------------- updateShoppingcart

    @Test
    fun `updateShoppingcart replaces the items (set semantics) and sets lastUpdatedAt`() {
        val user = newUser()
        val p1 = newProductVariant()
        val p2 = newProductVariant()
        createItem(user, p1)
        val result = gql(
            UPDATE_CART,
            mapOf("i" to mapOf("id" to "$user", "shoppingCartItems" to listOf(
                mapOf("count" to 2, "productVariantId" to "$p1"), mapOf("count" to 2, "productVariantId" to "$p1"),
                mapOf("count" to 3, "productVariantId" to "$p1"), mapOf("count" to 1, "productVariantId" to "$p2")
            ))),
            buyer(user)
        ).json["data"]["updateShoppingcart"]
        val nodes = result["shoppingcartItems"]["nodes"]
        assertThat(result["shoppingcartItems"]["totalCount"].asInt()).isEqualTo(3)
        assertThat(nodes.items().map { it["count"].asInt() to it["productVariant"]["id"].asString() })
            .containsExactlyInAnyOrder(2 to "$p1", 3 to "$p1", 1 to "$p2")
        assertThat(nodes.items().map { it["addedAt"].asString() }.toSet()).containsExactly(result["lastUpdatedAt"].asString())
        assertThat(nodes.items().map { it["id"].asString() }).isEqualTo(nodes.items().map { it["id"].asString() }.sorted())

        val empty = gql(UPDATE_CART, mapOf("i" to mapOf("id" to "$user", "shoppingCartItems" to emptyList<Any>())), buyer(user))
            .json["data"]["updateShoppingcart"]
        assertThat(empty["shoppingcartItems"]["totalCount"].asInt()).isZero()
        assertThat(OffsetDateTime.parse(empty["lastUpdatedAt"].asString()))
            .isAfterOrEqualTo(OffsetDateTime.parse(result["lastUpdatedAt"].asString()))
    }

    @Test
    fun `updateShoppingcart - null items, validation order and messages`() {
        val user = newUser()
        val p1 = newProductVariant()
        val unknown = UUID.randomUUID()
        val admin = authorizedUserHeader(UUID.randomUUID(), "admin")
        createItem(user, p1)
        val before = cart(user).json["data"]["_entities"][0]["shoppingcart"]

        // null list: no validation, no write
        val unchanged = gql(UPDATE_CART, mapOf("i" to mapOf("id" to "$user")), buyer(user)).json["data"]["updateShoppingcart"]
        assertThat(unchanged["lastUpdatedAt"]).isEqualTo(before["lastUpdatedAt"])
        assertThat(unchanged["shoppingcartItems"]["totalCount"].asInt()).isEqualTo(1)
        assertThat(gql(UPDATE_CART, mapOf("i" to mapOf("id" to "$unknown")), admin).body)
            .isEqualTo(error("ShoppingCart with UUID: `$unknown` not found.", listOf("updateShoppingcart"), 1, 42))
        // the product variant is validated before the user
        assertThat(gql(UPDATE_CART, mapOf("i" to mapOf("id" to "$unknown", "shoppingCartItems" to listOf(
            mapOf("count" to 1, "productVariantId" to "${UUID.randomUUID()}")))), admin).json["errors"][0]["message"].asString())
            .startsWith("Product variant with the UUID: ")
        assertThat(gql(UPDATE_CART, mapOf("i" to mapOf("id" to "$unknown", "shoppingCartItems" to listOf(
            mapOf("count" to 1, "productVariantId" to "$p1")))), admin).body)
            .isEqualTo(error("misarch_shoppingcart::graphql::model::user::User with UUID: `$unknown` not found.", listOf("updateShoppingcart"), 1, 42))
        assertThat(gql(UPDATE_CART, mapOf("i" to mapOf("id" to "$user", "shoppingCartItems" to emptyList<Any>())), buyer(UUID.randomUUID()))
            .json["errors"][0]["message"].asString()).startsWith("Authentication failed for user of UUID: ")
        assertThat(gql(UPDATE_CART, mapOf("i" to mapOf("id" to "$user", "shoppingCartItems" to listOf(
            mapOf("count" to -1, "productVariantId" to "$p1"))))).body).isEqualTo(
            error(
                "Failed to parse \"Int\": Invalid number (occurred while parsing \"ShoppingCartItemInput\") " +
                    "(occurred while parsing \"[ShoppingCartItemInput!]\") (occurred while parsing \"UpdateShoppingCartInput\")",
                listOf("updateShoppingcart"), 1, 68
            )
        )
        assertThat(cart(user).json["data"]["_entities"][0]["shoppingcart"]).isEqualTo(before)
    }

    @Test
    fun `updateShoppingcart validates every product variant (fix C2)`() {
        val user = newUser()
        val known = newProductVariant()
        val known2 = newProductVariant()
        val (unknown1, unknown2) = UUID.randomUUID() to UUID.randomUUID()
        createItem(user, known)
        val before = cart(user).json
        fun update(vararg variants: UUID) = gql(
            UPDATE_CART,
            mapOf("i" to mapOf("id" to "$user", "shoppingCartItems" to variants.map { mapOf("count" to 1, "productVariantId" to "$it") })),
            buyer(user)
        ).json
        // one unknown variant among known ones always fails (the original: with probability k/n), naming the first
        repeat(10) {
            assertThat(update(known, unknown1, known2, unknown2)["errors"][0]["message"].asString())
                .isEqualTo("Product variant with the UUID: `$unknown1` is not present in the system.")
            assertThat(update(unknown2, known)["errors"][0]["message"].asString())
                .isEqualTo("Product variant with the UUID: `$unknown2` is not present in the system.")
        }
        assertThat(cart(user).json).isEqualTo(before)
        val stored = update(known, known2)
        assertThat(stored["errors"]).isNull()
        assertThat(stored["data"]["updateShoppingcart"]["shoppingcartItems"]["totalCount"].asInt()).isEqualTo(2)
    }

    // ---------------------------------------------------------------- async-graphql execution semantics

    @Test
    fun `a failing root field nulls data, reports only the first error and stops later mutations`() {
        val user = newUser()
        val variant = newProductVariant()
        val unknown = UUID.randomUUID()
        // query root fields run concurrently: the error of the one that completes first is reported (as in
        // async-graphql), so either field's
        assertThat(gql("""query { a: shoppingcartItem(id: "$unknown") { id } b: shoppingcartItem(id: "$unknown") { id } }""").body)
            .isIn(
                error("ShoppingCartItem of UUID: `$unknown` not found.", listOf("a"), 1, 9),
                error("ShoppingCartItem of UUID: `$unknown` not found.", listOf("b"), 1, 80),
            )
        val result = gql(
            """mutation { a: deleteShoppingcartItem(id: "$unknown") b: createShoppingcartItem(input: {id: "$user", shoppingCartItem: {count: 1, productVariantId: "$variant"}}) { id } }""",
            user = buyer(user)
        )
        assertThat(result.body).isEqualTo(error("ShoppingCartItem of UUID: `$unknown` not found.", listOf("a"), 1, 12))
        assertThat(cart(user).json["data"]["_entities"][0]["shoppingcart"]["shoppingcartItems"]["totalCount"].asInt()).isZero()
        assertThat(dapr.published).isEmpty()
    }

    companion object {
        const val ITEM_FIELDS = "id count addedAt productVariant { id }"
        const val CREATE = "mutation(\$i: CreateShoppingCartItemInput!) { createShoppingcartItem(input: \$i) { $ITEM_FIELDS } }"
        const val CART = "query(\$r: [_Any!]!) { _entities(representations: \$r) { __typename ... on User { id shoppingcart { " +
            "lastUpdatedAt shoppingcartItems%s { totalCount hasNextPage nodes { %s } } } } } }"
        const val ITEM_QUERY = "query(\$id: UUID!) { shoppingcartItem(id: \$id) { $ITEM_FIELDS } }"
        const val UPDATE_ITEM = "mutation(\$i: UpdateShoppingCartItemInput!) { updateShoppingcartItem(input: \$i) { $ITEM_FIELDS } }"
        const val DELETE = "mutation(\$id: UUID!) { deleteShoppingcartItem(id: \$id) }"
        const val UPDATE_CART = "mutation(\$i: UpdateShoppingCartInput!) { updateShoppingcart(input: \$i) { lastUpdatedAt " +
            "shoppingcartItems { totalCount hasNextPage nodes { id count addedAt productVariant { id } } } } }"

        /** Verbatim content of order's `queries/get_shopping_cart_product_variant_ids_and_counts.graphql`. */
        val ORDER_QUERY = """
            query GetShoppingCartProductVariantIdsAndCounts(${'$'}representations: [_Any!]!) {
                _entities(representations: ${'$'}representations) {
                    __typename
                    ... on User {
                        shoppingcart {
                            shoppingcartItems {
                                nodes {
                                    id,
                                    productVariant {
                                        id
                                    },
                                    count
                                }
                            }
                        }
                    }
                }
            }
        """.trimIndent()
    }
}
