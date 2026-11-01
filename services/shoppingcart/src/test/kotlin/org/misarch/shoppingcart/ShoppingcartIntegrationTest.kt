package org.misarch.shoppingcart

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.misarch.common.testing.MisarchIntegrationTest
import org.misarch.common.testing.SqlStatementLog
import org.springframework.context.annotation.Import
import tools.jackson.databind.JsonNode
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Behaviour of the shopping cart service against a real Postgres (Testcontainers) and a fake Dapr sidecar:
 * event subscriptions, mutations, queries, federation entities (incl. order's `createOrder` invocation),
 * authorization and the statements of the write paths.
 */
@Import(SqlStatementLog::class)
class ShoppingcartIntegrationTest : MisarchIntegrationTest() {

    override val graphqlPath: String = "/"

    private fun registerUser(): UUID = UUID.randomUUID().also {
        assertThat(deliverEvent("/subscription/user/user/created", "user/user/created", """{"id":"$it","username":"u"}"""))
            .isEqualTo(200)
    }

    private fun registerProductVariant(): UUID = UUID.randomUUID().also {
        assertThat(
            deliverEvent(
                "/subscription/catalog/product-variant/created", "catalog/product-variant/created",
                """{"id":"$it","productId":"${UUID.randomUUID()}","isDefault":true}"""
            )
        ).isEqualTo(200)
    }


    private val employee = authorizedUserHeader(UUID.randomUUID(), "employee")

    private fun createItem(user: UUID, productVariant: UUID, count: Int = 1, header: String? = buyer(user)): JsonNode =
        graphql(
            CREATE,
            mapOf("i" to mapOf("id" to "$user", "shoppingCartItem" to mapOf("count" to count, "productVariantId" to "$productVariant"))),
            header
        )

    private fun createdItemId(user: UUID, productVariant: UUID, count: Int = 1): String =
        createItem(user, productVariant, count).also { assertThat(it["errors"]).isNull() }["data"]["createShoppingcartItem"]["id"].asString()

    private fun cart(user: UUID, args: String = "", fields: String = ITEM_FIELDS): JsonNode =
        graphql(CART.format(args, fields), mapOf("r" to listOf(mapOf("__typename" to "User", "id" to "$user"))))

    private fun shoppingcart(user: UUID, args: String = ""): JsonNode =
        cart(user, args).also { assertThat(it["errors"]).isNull() }["data"]["_entities"][0]["shoppingcart"]

    private fun lastUpdatedAt(user: UUID): OffsetDateTime = OffsetDateTime.parse(shoppingcart(user)["lastUpdatedAt"].asString())


    private fun JsonNode.ids(): List<String> = iterator().asSequence().map { it["id"].asString() }.toList()

    /** Waits until the wall clock (ms) has advanced, so that a new `now` differs from earlier timestamps. */
    private fun nextMillisecond() = Thread.sleep(5)

    @Test
    fun `dapr subscribe lists the three topics`() {
        val body = webTestClient.get().uri("/dapr/subscribe").exchange()
            .expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!
        val subscriptions = mapper.readTree(body).iterator().asSequence()
            .associate { it["topic"].asString() to (it["pubsubName"].asString() to it["route"].asString()) }
        assertThat(subscriptions).containsOnly(
            org.assertj.core.api.Assertions.entry("user/user/created", "pubsub" to "/subscription/user/user/created"),
            org.assertj.core.api.Assertions.entry(
                "catalog/product-variant/created", "pubsub" to "/subscription/catalog/product-variant/created"
            ),
            org.assertj.core.api.Assertions.entry("order/order/created", "pubsub" to "/subscription/order/order/created"),
        )
    }

    @Test
    fun `health endpoint is UP`() {
        webTestClient.get().uri("/health").exchange()
            .expectStatus().isOk
            .expectBody().json("""{"status":"UP"}""", org.springframework.test.json.JsonCompareMode.STRICT)
    }

    @Test
    fun `replica events are idempotent`() {
        val user = registerUser()
        val variant = registerProductVariant()
        createdItemId(user, variant)
        val before = cart(user)
        nextMillisecond()
        assertThat(deliverEvent("/subscription/user/user/created", "user/user/created", """{"id":"$user"}""")).isEqualTo(200)
        assertThat(
            deliverEvent("/subscription/catalog/product-variant/created", "catalog/product-variant/created", """{"id":"$variant"}""")
        ).isEqualTo(200)
        assertThat(cart(user)).isEqualTo(before)
        assertThat(deliverEvent("/subscription/user/user/created", "user/user/created", """{"name":"x"}""")).isEqualTo(400)
        assertThat(dapr.published).isEmpty()
    }

    @Test
    fun `order created removes the ordered items of that user only, idempotently`() {
        val user = registerUser()
        val other = registerUser()
        val variant = registerProductVariant()
        val item1 = createdItemId(user, variant)
        val item2 = createdItemId(user, registerProductVariant())
        val otherItem = createdItemId(other, variant)
        val lastUpdatedAt = lastUpdatedAt(user)
        val otherLastUpdatedAt = lastUpdatedAt(other)
        nextMillisecond()

        val order = """{"id":"${UUID.randomUUID()}","userId":"$user","orderStatus":"PLACED","orderItems":[
            {"id":"${UUID.randomUUID()}","shoppingCartItemId":"$item1","count":1,"compensatableAmount":20,"discountIds":[]},
            {"id":"${UUID.randomUUID()}","shoppingCartItemId":"$otherItem","count":1},
            {"id":"${UUID.randomUUID()}","shoppingCartItemId":"${UUID.randomUUID()}","count":3}]}"""
        // one DELETE for all ordered items (ids bound as one array) and the cart's lastUpdatedAt
        val statements = SqlStatementLog.record {
            assertThat(deliverEvent("/subscription/order/order/created", "order/order/created", order)).isEqualTo(200)
        }.filterNot { it in setOf("BEGIN", "COMMIT") }
        assertThat(statements).hasSize(2)
        assertThat(statements[0]).startsWith("DELETE FROM ShoppingCartItemEntity").contains("ANY(")
        assertThat(statements[1]).startsWith("UPDATE UserEntity")

        val afterOrder = lastUpdatedAt(user)
        assertThat(afterOrder).isAfter(lastUpdatedAt)
        nextMillisecond()
        assertThat(deliverEvent("/subscription/order/order/created", "order/order/created", order)).isEqualTo(200)
        assertThat(lastUpdatedAt(user)).isEqualTo(afterOrder)
        assertThat(
            deliverEvent(
                "/subscription/order/order/created", "order/order/created",
                """{"id":"${UUID.randomUUID()}","userId":"${UUID.randomUUID()}","orderItems":[]}"""
            )
        ).isEqualTo(200)

        assertThat(shoppingcart(user)["shoppingcartItems"]["nodes"].ids()).containsExactly(item2)
        assertThat(shoppingcart(other)["shoppingcartItems"]["totalCount"].asInt()).isEqualTo(1)
        assertThat(lastUpdatedAt(other)).isEqualTo(otherLastUpdatedAt)
    }

    @Test
    fun `createShoppingcartItem - authorization and validation`() {
        val user = registerUser()
        val variant = registerProductVariant()
        val unknown = UUID.randomUUID()
        assertThat(errorMessage(createItem(user, variant, header = null))).endsWith("Unauthorized access")
        assertThat(errorMessage(createItem(user, variant, header = buyer(UUID.randomUUID()))))
            .contains("is not an employee or admin")
        assertThat(errorMessage(createItem(unknown, variant, header = employee)))
            .endsWith("User with id $unknown does not exist")
        assertThat(errorMessage(createItem(user, unknown))).endsWith("Product variant with id $unknown does not exist")
        assertThat(errorMessage(createItem(user, variant, count = -1))).endsWith("Count must not be negative")
        assertThat(shoppingcart(user)["shoppingcartItems"]["totalCount"].asInt()).isZero()
        assertThat(createItem(user, variant, header = employee)["errors"]).isNull()
    }

    @Test
    fun `createShoppingcartItem reuses the item of the same variant and sets lastUpdatedAt`() {
        val user = registerUser()
        val variant = registerProductVariant()
        val before = lastUpdatedAt(user)
        nextMillisecond()
        val first = createItem(user, variant, 1)["data"]["createShoppingcartItem"]
        assertThat(first["count"].asInt()).isEqualTo(1)
        assertThat(first["productVariant"]["id"].asString()).isEqualTo("$variant")
        assertThat(first["addedAt"].asString()).endsWith("Z")
        assertThat(shoppingcart(user)["lastUpdatedAt"].asString()).isEqualTo(first["addedAt"].asString())
        assertThat(OffsetDateTime.parse(first["addedAt"].asString())).isAfter(before)
        nextMillisecond()
        // the same variant again: the existing item is returned unchanged, nothing is added
        assertThat(createItem(user, variant, 2)["data"]["createShoppingcartItem"]).isEqualTo(first)
        assertThat(shoppingcart(user)["lastUpdatedAt"].asString()).isEqualTo(first["addedAt"].asString())
        val zero = createItem(user, registerProductVariant(), 0)["data"]["createShoppingcartItem"]
        assertThat(zero["count"].asInt()).isZero()
        shoppingcart(user).let {
            assertThat(it["lastUpdatedAt"].asString()).isEqualTo(zero["addedAt"].asString())
            assertThat(it["shoppingcartItems"]["totalCount"].asInt()).isEqualTo(2)
        }

        // concurrent creations of one variant for one user create one item
        val concurrentVariant = registerProductVariant()
        val ids = (1..4).map { CompletableFuture.supplyAsync { createdItemId(user, concurrentVariant) } }.map { it.get() }
        assertThat(ids.toSet()).hasSize(1)
        assertThat(shoppingcart(user)["shoppingcartItems"]["totalCount"].asInt()).isEqualTo(3)
    }

    @Test
    fun `createShoppingcartItem statements - lock the user, check the variant, insert, touch the cart`() {
        val user = registerUser()
        val variant = registerProductVariant()
        val statements = SqlStatementLog.record { createItem(user, variant) }.filterNot { it in setOf("BEGIN", "COMMIT") }
        assertThat(statements).hasSize(5)
        assertThat(statements[0]).contains("FROM UserEntity").contains("FOR UPDATE")
        assertThat(statements[1]).containsIgnoringCase("FROM \"ProductVariantEntity\"").contains("ANY(")
        assertThat(statements[2]).containsIgnoringCase("FROM \"ShoppingCartItemEntity\"")
        assertThat(statements[3]).startsWithIgnoringCase("INSERT INTO \"ShoppingCartItemEntity\"")
        assertThat(statements[4]).startsWith("UPDATE UserEntity")
    }

    @Test
    fun `shopping cart items - order by id, pagination`() {
        val user = registerUser()
        val ids = (1..6).map { createdItemId(user, registerProductVariant(), it) }.sorted()

        cart(user)["data"]["_entities"][0].let {
            assertThat(it["__typename"].asString()).isEqualTo("User")
            assertThat(it["id"].asString()).isEqualTo("$user")
            val connection = it["shoppingcart"]["shoppingcartItems"]
            assertThat(connection["totalCount"].asInt()).isEqualTo(6)
            assertThat(connection["hasNextPage"].asBoolean()).isFalse()
            assertThat(connection["nodes"].ids()).isEqualTo(ids)
        }
        shoppingcart(user, "(first: 2, skip: 1, orderBy: {direction: DESC})")["shoppingcartItems"].let {
            assertThat(it["nodes"].ids()).isEqualTo(ids.reversed().subList(1, 3))
            assertThat(it["hasNextPage"].asBoolean()).isTrue()
        }
        shoppingcart(user, "(first: 0)")["shoppingcartItems"].let {
            assertThat(it["nodes"].size()).isZero()
            assertThat(it["hasNextPage"].asBoolean()).isTrue()
        }
        shoppingcart(user, "(first: 2, skip: 4)")["shoppingcartItems"].let {
            assertThat(it["nodes"].ids()).isEqualTo(ids.subList(4, 6))
            assertThat(it["hasNextPage"].asBoolean()).isFalse()
        }
        shoppingcart(user, "(skip: 10, orderBy: {field: ID})")["shoppingcartItems"].let {
            assertThat(it["nodes"].size()).isZero()
            assertThat(it["hasNextPage"].asBoolean()).isFalse()
        }
        assertThat(errorMessage(cart(user, "(first: -1)", "id"))).endsWith("must not be negative.")
        assertThat(errorMessage(cart(user, "(skip: -1)", "id"))).endsWith("Offset must not be negative.")
    }

    @Test
    fun `the user entity reads only its UserEntity row, the items are read only when selected`() {
        val user = registerUser()
        repeat(3) { createdItemId(user, registerProductVariant()) }
        val representations = mapOf("r" to listOf(mapOf("__typename" to "User", "id" to "$user")))
        fun record(query: String, variables: Map<String, Any?> = representations): List<String> = SqlStatementLog.record {
            assertThat(graphql(query, variables)["errors"]).isNull()
        }
        fun itemStatements(statements: List<String>) = statements.filter { it.contains("ShoppingCartItemEntity", ignoreCase = true) }

        val cartOnly = record("query(\$r: [_Any!]!) { _entities(representations: \$r) { ... on User { id shoppingcart { lastUpdatedAt } } } }")
        assertThat(cartOnly).singleElement().satisfies({ assertThat(it).containsIgnoringCase("FROM \"UserEntity\"") })

        // order's createOrder invocation: the user row and one page of the items (no count, no probe)
        val nodesOnly = record(ORDER_QUERY, mapOf("representations" to representations["r"]))
        assertThat(nodesOnly).hasSize(2)
        assertThat(itemStatements(nodesOnly)).singleElement().satisfies({ assertThat(it).startsWithIgnoringCase("SELECT t.* FROM \"ShoppingCartItemEntity\" t") })

        val all = record(CART.format("(first: 2)", "id"))
        assertThat(itemStatements(all)).hasSize(3)
        assertThat(itemStatements(all)).anyMatch { it.startsWith("SELECT COUNT(t.id)") }.anyMatch { it.startsWith("SELECT 1 FROM") }
    }

    @Test
    fun `entities - users and items without authorization, unknown ids resolve to null with an error`() {
        val user = registerUser()
        val variant = registerProductVariant()
        val item = createItem(user, variant, 7)["data"]["createShoppingcartItem"]
        val result = graphql(
            "query(\$r: [_Any!]!) { _entities(representations: \$r) { __typename ... on ShoppingCartItem { $ITEM_FIELDS } ... on User { id } } }",
            mapOf("r" to listOf(
                mapOf("__typename" to "ShoppingCartItem", "id" to item["id"].asString()),
                mapOf("__typename" to "User", "id" to "$user"),
                mapOf("__typename" to "User", "id" to "${UUID.randomUUID()}"),
            ))
        )
        val entities = result["data"]["_entities"]
        assertThat(entities[0]["__typename"].asString()).isEqualTo("ShoppingCartItem")
        assertThat(entities[0]["count"].asInt()).isEqualTo(7)
        assertThat(entities[0]["addedAt"]).isEqualTo(item["addedAt"])
        assertThat(entities[0]["productVariant"]["id"].asString()).isEqualTo("$variant")
        assertThat(entities[1]["id"].asString()).isEqualTo("$user")
        assertThat(entities[2].isNull).isTrue()
        assertThat(result["errors"]).hasSize(1)
    }

    @Test
    fun `order service invocation contract - the request of createOrder`() {
        val user = registerUser()
        val items = (1..3).map { index -> registerProductVariant().let { createdItemId(user, it, index) to it } }
        val body = mapper.writeValueAsString(
            linkedMapOf(
                "variables" to mapOf("representations" to listOf(mapOf("__typename" to "User", "id" to "$user"))),
                "query" to ORDER_QUERY,
                "operationName" to "GetShoppingCartProductVariantIdsAndCounts"
            )
        )
        fun invoke(body: String): JsonNode = mapper.readTree(
            webTestClient.post().uri("/").header("Content-Type", "application/json")
                .header("Authorized-User", buyer(user)).bodyValue(body).exchange()
                .expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!
        )
        val expectedNodes = items.sortedBy { it.first }.map { (id, variant) ->
            """{"id":"$id","productVariant":{"id":"$variant"},"count":${items.indexOfFirst { it.first == id } + 1}}"""
        }
        assertThat(mapper.writeValueAsString(invoke(body))).isEqualTo(
            """{"data":{"_entities":[{"__typename":"User","shoppingcart":{"shoppingcartItems":{"nodes":[${expectedNodes.joinToString(",")}]}}}]}}"""
        )
        // unknown user (not replicated): no data for the user -> order fails the createOrder
        val unknown = invoke(body.replace("$user", "${UUID.randomUUID()}"))
        assertThat(unknown["data"]["_entities"][0].isNull).isTrue()
        assertThat(unknown["errors"]).isNotEmpty()
    }

    @Test
    fun `shoppingcartItem, updateShoppingcartItem and deleteShoppingcartItem - existence, authorization, lastUpdatedAt`() {
        val user = registerUser()
        val other = UUID.randomUUID()
        val unknown = UUID.randomUUID()
        val item = createItem(user, registerProductVariant())["data"]["createShoppingcartItem"]
        val itemId = item["id"].asString()
        val lastUpdatedAt = lastUpdatedAt(user)
        nextMillisecond()

        assertThat(errorMessage(graphql(ITEM_QUERY, mapOf("id" to "$unknown"), buyer(user)))).endsWith(": null")
        assertThat(errorMessage(graphql(ITEM_QUERY, mapOf("id" to itemId)))).endsWith("Unauthorized access")
        assertThat(errorMessage(graphql(ITEM_QUERY, mapOf("id" to itemId), buyer(other)))).contains("is not an employee or admin")
        assertThat(graphql(ITEM_QUERY, mapOf("id" to itemId), buyer(user))["data"]["shoppingcartItem"]).isEqualTo(item)
        assertThat(graphql(ITEM_QUERY, mapOf("id" to itemId), employee)["data"]["shoppingcartItem"]).isEqualTo(item)

        assertThat(errorMessage(graphql(UPDATE_ITEM, mapOf("i" to mapOf("id" to "$unknown", "count" to 5)), buyer(user))))
            .endsWith(": null")
        assertThat(errorMessage(graphql(UPDATE_ITEM, mapOf("i" to mapOf("id" to itemId, "count" to 5)), buyer(other))))
            .contains("is not an employee or admin")
        assertThat(errorMessage(graphql(UPDATE_ITEM, mapOf("i" to mapOf("id" to itemId, "count" to -1)), buyer(user))))
            .endsWith("Count must not be negative")
        assertThat(lastUpdatedAt(user)).isEqualTo(lastUpdatedAt)
        val updated = graphql(UPDATE_ITEM, mapOf("i" to mapOf("id" to itemId, "count" to 5)), buyer(user))["data"]["updateShoppingcartItem"]
        assertThat(updated["count"].asInt()).isEqualTo(5)
        assertThat(updated["addedAt"]).isEqualTo(item["addedAt"])
        val afterUpdate = lastUpdatedAt(user)
        assertThat(afterUpdate).isAfter(lastUpdatedAt)
        nextMillisecond()

        assertThat(errorMessage(graphql(DELETE, mapOf("id" to itemId), buyer(other)))).contains("is not an employee or admin")
        assertThat(graphql(DELETE, mapOf("id" to itemId), buyer(user))["data"]["deleteShoppingcartItem"].asBoolean()).isTrue()
        assertThat(errorMessage(graphql(DELETE, mapOf("id" to itemId), buyer(user)))).endsWith(": null")
        assertThat(lastUpdatedAt(user)).isAfter(afterUpdate)
        assertThat(shoppingcart(user)["shoppingcartItems"]["totalCount"].asInt()).isZero()
    }

    @Test
    fun `updateShoppingcart replaces the items (identical items collapse) and sets lastUpdatedAt`() {
        val user = registerUser()
        val p1 = registerProductVariant()
        val p2 = registerProductVariant()
        createdItemId(user, p1)
        val result = graphql(
            UPDATE_CART,
            mapOf("i" to mapOf("id" to "$user", "shoppingCartItems" to listOf(
                mapOf("count" to 2, "productVariantId" to "$p1"), mapOf("count" to 2, "productVariantId" to "$p1"),
                mapOf("count" to 3, "productVariantId" to "$p1"), mapOf("count" to 1, "productVariantId" to "$p2")
            ))),
            buyer(user)
        )["data"]["updateShoppingcart"]
        val nodes = result["shoppingcartItems"]["nodes"].iterator().asSequence().toList()
        assertThat(result["shoppingcartItems"]["totalCount"].asInt()).isEqualTo(3)
        assertThat(nodes.map { it["count"].asInt() to it["productVariant"]["id"].asString() })
            .containsExactlyInAnyOrder(2 to "$p1", 3 to "$p1", 1 to "$p2")
        assertThat(nodes.map { it["addedAt"].asString() }.toSet()).containsExactly(result["lastUpdatedAt"].asString())

        val empty = graphql(
            UPDATE_CART, mapOf("i" to mapOf("id" to "$user", "shoppingCartItems" to emptyList<Any>())), buyer(user)
        )["data"]["updateShoppingcart"]
        assertThat(empty["shoppingcartItems"]["totalCount"].asInt()).isZero()
        assertThat(OffsetDateTime.parse(empty["lastUpdatedAt"].asString()))
            .isAfterOrEqualTo(OffsetDateTime.parse(result["lastUpdatedAt"].asString()))
    }

    @Test
    fun `updateShoppingcart - authorization, null items, unknown user and product variants`() {
        val user = registerUser()
        val known = registerProductVariant()
        val known2 = registerProductVariant()
        val unknown = UUID.randomUUID()
        createdItemId(user, known)
        val before = shoppingcart(user)
        fun update(id: UUID, variants: List<UUID>?, header: String? = buyer(user)) = graphql(
            UPDATE_CART,
            mapOf("i" to mapOf("id" to "$id", "shoppingCartItems" to variants?.map { mapOf("count" to 1, "productVariantId" to "$it") })),
            header
        )

        // null list: no validation, no write
        val unchanged = update(user, null)["data"]["updateShoppingcart"]
        assertThat(unchanged["lastUpdatedAt"]).isEqualTo(before["lastUpdatedAt"])
        assertThat(unchanged["shoppingcartItems"]["totalCount"].asInt()).isEqualTo(1)
        assertThat(errorMessage(update(unknown, null, employee))).endsWith(": null")
        assertThat(errorMessage(update(unknown, listOf(known), employee))).endsWith("User with id $unknown does not exist")
        assertThat(errorMessage(update(user, emptyList(), buyer(UUID.randomUUID())))).contains("is not an employee or admin")
        assertThat(errorMessage(update(user, emptyList(), null))).endsWith("Unauthorized access")
        // every product variant is validated, the first unknown one is reported
        val unknown2 = UUID.randomUUID()
        assertThat(errorMessage(update(user, listOf(known, unknown, known2, unknown2))))
            .endsWith("Product variant with id $unknown does not exist")
        assertThat(errorMessage(update(user, listOf(unknown2, known)))).endsWith("Product variant with id $unknown2 does not exist")
        assertThat(shoppingcart(user)).isEqualTo(before)
        assertThat(update(user, listOf(known, known2))["data"]["updateShoppingcart"]["shoppingcartItems"]["totalCount"].asInt())
            .isEqualTo(2)
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
