package org.misarch.wishlist

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.entry
import org.junit.jupiter.api.Test
import org.misarch.common.testing.MisarchIntegrationTest
import org.misarch.common.testing.SqlStatementLog
import org.springframework.context.annotation.Import
import tools.jackson.databind.JsonNode
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Behaviour of the wishlist service against a real Postgres (Testcontainers) and a fake Dapr sidecar: event
 * subscriptions, mutations with authorization and validation, queries, connections (paging, order, lazy statements),
 * federation entities and the statements of the write paths.
 */
@Import(SqlStatementLog::class)
class WishlistIntegrationTest : MisarchIntegrationTest() {

    override val graphqlPath: String = "/"

    private fun registerUser(): UUID = UUID.randomUUID().also {
        assertThat(deliverEvent("/subscription/user/user/created", "user/user/created", """{"id":"$it","username":"u"}"""))
            .isEqualTo(200)
    }

    private fun registerProductVariant(): UUID = UUID.randomUUID().also {
        assertThat(
            deliverEvent("/subscription/catalog/product-variant/created", "catalog/product-variant/created", """{"id":"$it","productId":"$it"}""")
        ).isEqualTo(200)
    }

    private fun buyer(id: UUID) = authorizedUserHeader(id, "buyer")

    private val employee = authorizedUserHeader(UUID.randomUUID(), "employee")

    private fun create(user: UUID, variants: List<UUID>, name: String = "w", header: String? = buyer(user)): JsonNode =
        graphql(CREATE, mapOf("i" to mapOf("userId" to "$user", "productVariantIds" to variants.map { "$it" }, "name" to name)), header)

    private fun createdId(user: UUID, variants: List<UUID>, name: String = "w"): String =
        create(user, variants, name).also { assertThat(it["errors"]).isNull() }["data"]["createWishlist"]["id"].asString()

    private fun update(id: String, variants: List<UUID>?, name: String?, header: String?): JsonNode =
        graphql(UPDATE, mapOf("i" to mapOf("id" to id, "productVariantIds" to variants?.map { "$it" }, "name" to name)), header)

    private fun wishlist(id: String, header: String? = employee): JsonNode = graphql(WISHLIST, mapOf("id" to id), header)

    private fun variantsOf(id: String): List<String> =
        wishlist(id)["data"]["wishlist"]["productVariants"]["nodes"].iterator().asSequence().map { it["id"].asString() }.toList()

    private fun wishlists(user: UUID, args: String = "", header: String? = buyer(user)): JsonNode =
        graphql(LIST.format(args), mapOf("r" to listOf(mapOf("__typename" to "User", "id" to "$user"))), header)

    private fun JsonNode.ids(): List<String> = iterator().asSequence().map { it["id"].asString() }.toList()

    private fun errorMessage(result: JsonNode): String = result["errors"][0]["message"].asString()

    private fun statementsOf(block: () -> Unit) = SqlStatementLog.record(block).filterNot { it in setOf("BEGIN", "COMMIT") }

    @Test
    fun `dapr subscribe lists the two topics`() {
        val body = webTestClient.get().uri("/dapr/subscribe").exchange()
            .expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!
        val subscriptions = mapper.readTree(body).iterator().asSequence()
            .associate { it["topic"].asString() to (it["pubsubName"].asString() to it["route"].asString()) }
        assertThat(subscriptions).containsOnly(
            entry("user/user/created", "pubsub" to "/subscription/user/user/created"),
            entry("catalog/product-variant/created", "pubsub" to "/subscription/catalog/product-variant/created"),
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
        assertThat(deliverEvent("/subscription/user/user/created", "user/user/created", """{"id":"$user"}""")).isEqualTo(200)
        assertThat(
            deliverEvent("/subscription/catalog/product-variant/created", "catalog/product-variant/created", """{"id":"$variant"}""")
        ).isEqualTo(200)
        assertThat(variantsOf(createdId(user, listOf(variant)))).containsExactly("$variant")
        assertThat(deliverEvent("/subscription/user/user/created", "user/user/created", """{"name":"x"}""")).isEqualTo(400)
        assertThat(dapr.published).isEmpty()
    }

    @Test
    fun `createWishlist - authorization, validation and result`() {
        val user = registerUser()
        val (v1, v2) = registerProductVariant() to registerProductVariant()
        val unknown = UUID.randomUUID()
        assertThat(errorMessage(create(user, listOf(v1), header = null))).endsWith("Unauthorized access")
        assertThat(errorMessage(create(user, listOf(v1), header = buyer(UUID.randomUUID())))).contains("is not an employee or admin")
        // every product variant is validated, the first unknown one is reported; variants before the user
        assertThat(errorMessage(create(user, listOf(v1, unknown, v2, UUID.randomUUID()))))
            .endsWith("Product variant with id $unknown does not exist")
        assertThat(errorMessage(create(unknown, listOf(unknown), header = employee)))
            .endsWith("Product variant with id $unknown does not exist")
        assertThat(errorMessage(create(unknown, listOf(v1), header = employee))).endsWith("User with id $unknown does not exist")
        assertThat(wishlists(user)["data"]["_entities"][0]["wishlists"]["totalCount"].asInt()).isZero()

        val created = create(user, listOf(v2, v1, v2), "Birthday")["data"]["createWishlist"]
        assertThat(created["name"].asString()).isEqualTo("Birthday")
        assertThat(created["user"]["id"].asString()).isEqualTo("$user")
        assertThat(created["createdAt"].asString()).isEqualTo(created["lastUpdatedAt"].asString()).endsWith("Z")
        assertThat(created["productVariants"]["totalCount"].asInt()).isEqualTo(2)
        assertThat(created["productVariants"]["nodes"].ids()).isEqualTo(listOf("$v1", "$v2").sorted())
        val empty = create(user, emptyList(), header = employee)["data"]["createWishlist"]
        assertThat(empty["productVariants"]["totalCount"].asInt()).isZero()
    }

    @Test
    fun `createWishlist statements - one query per check, one INSERT for all product variants`() {
        val user = registerUser()
        val variants = (1..3).map { registerProductVariant() }
        val statements = statementsOf { create(user, variants) }.filterNot { "INNER JOIN" in it }
        assertThat(statements).hasSize(4)
        assertThat(statements[0]).containsIgnoringCase("FROM \"ProductVariantEntity\"").contains("ANY(")
        assertThat(statements[1]).containsIgnoringCase("FROM \"UserEntity\"")
        assertThat(statements[2]).startsWithIgnoringCase("INSERT INTO \"WishlistEntity\"")
        assertThat(statements[3]).startsWith("INSERT INTO WishlistToProductVariantEntity").contains("unnest(")
    }

    @Test
    fun `wishlist query and Wishlist entity - existence before authorization`() {
        val user = registerUser()
        val id = createdId(user, listOf(registerProductVariant()))
        val unknown = UUID.randomUUID()
        assertThat(errorMessage(wishlist("$unknown", buyer(user)))).endsWith(": null")
        assertThat(errorMessage(wishlist(id, null))).endsWith("Unauthorized access")
        assertThat(errorMessage(wishlist(id, buyer(UUID.randomUUID())))).contains("is not an employee or admin")
        assertThat(wishlist(id, buyer(user))["data"]["wishlist"]["id"].asString()).isEqualTo(id)
        assertThat(wishlist(id)["data"]["wishlist"]["id"].asString()).isEqualTo(id)

        fun entity(header: String?) = graphql(
            "query(\$r: [_Any!]!) { _entities(representations: \$r) { ... on Wishlist { id name } } }",
            mapOf("r" to listOf(mapOf("__typename" to "Wishlist", "id" to id))), header
        )
        assertThat(entity(buyer(user))["data"]["_entities"][0]["id"].asString()).isEqualTo(id)
        entity(buyer(UUID.randomUUID())).let {
            // graphql-kotlin reports a failing entity resolver without the cause
            assertThat(it["data"]["_entities"][0].isNull).isTrue()
            assertThat(errorMessage(it)).contains("resolve federated type")
        }
    }

    @Test
    fun `entities User - the user must be replicated, User wishlists authorizes`() {
        val user = registerUser()
        createdId(user, emptyList(), "a")
        val unknown = UUID.randomUUID()
        val result = graphql(
            "query(\$r: [_Any!]!) { _entities(representations: \$r) { ... on User { id } } }",
            mapOf("r" to listOf(mapOf("__typename" to "User", "id" to "$user"), mapOf("__typename" to "User", "id" to "$unknown")))
        )
        assertThat(result["data"]["_entities"][0]["id"].asString()).isEqualTo("$user")
        assertThat(result["data"]["_entities"][1].isNull).isTrue()
        assertThat(errorMessage(wishlists(user, header = null))).endsWith("Unauthorized access")
        assertThat(errorMessage(wishlists(user, header = buyer(UUID.randomUUID())))).contains("is not an employee or admin")
        assertThat(wishlists(user, header = employee)["data"]["_entities"][0]["wishlists"]["totalCount"].asInt()).isEqualTo(1)
    }

    @Test
    fun `User wishlists - order fields, ties by id descending, pagination, default page size 25`() {
        val user = registerUser()
        val ids = listOf("b", "a", "c", "a").map { createdId(user, emptyList(), it) }
        fun order(args: String) = wishlists(user, args)["data"]["_entities"][0]["wishlists"]["nodes"].ids()
        assertThat(order("")).isEqualTo(ids.sorted())
        assertThat(order("(orderBy: {direction: DESC})")).isEqualTo(ids.sortedDescending())
        val aIds = listOf(ids[1], ids[3]).sortedDescending()
        assertThat(order("(orderBy: {field: NAME})")).isEqualTo(aIds + ids[0] + ids[2])
        assertThat(order("(orderBy: {field: NAME, direction: DESC})")).isEqualTo(listOf(ids[2], ids[0]) + aIds)
        assertThat(order("(orderBy: {field: CREATED_AT})")).isEqualTo(ids)
        assertThat(order("(orderBy: {field: USER_ID})")).isEqualTo(ids.sortedDescending())

        val page = wishlists(user, "(first: 2, skip: 1)")["data"]["_entities"][0]["wishlists"]
        assertThat(page["nodes"].ids()).isEqualTo(ids.sorted().subList(1, 3))
        assertThat(page["hasNextPage"].asBoolean()).isTrue()
        val last = wishlists(user, "(first: 2, skip: 2)")["data"]["_entities"][0]["wishlists"]
        assertThat(last["hasNextPage"].asBoolean()).isFalse()
        assertThat(errorMessage(wishlists(user, "(skip: -1)"))).endsWith("Offset must not be negative.")

        repeat(24) { createdId(user, emptyList(), "x") }
        val all = wishlists(user)["data"]["_entities"][0]["wishlists"]
        assertThat(all["totalCount"].asInt()).isEqualTo(28)
        assertThat(all["nodes"].size()).isEqualTo(25)
        assertThat(all["hasNextPage"].asBoolean()).isTrue()
    }

    @Test
    fun `wishlist product variants - order by id, pagination`() {
        val user = registerUser()
        val variants = (1..4).map { registerProductVariant() }
        val id = createdId(user, variants)
        val sorted = variants.map { "$it" }.sorted()
        fun connection(args: String) = graphql(
            "query(\$id: UUID!) { wishlist(id: \$id) { productVariants$args { totalCount hasNextPage nodes { id } } } }",
            mapOf("id" to id), employee
        )["data"]["wishlist"]["productVariants"]
        assertThat(connection("")["nodes"].ids()).isEqualTo(sorted)
        connection("(first: 2, skip: 1, orderBy: {direction: DESC})").let {
            assertThat(it["nodes"].ids()).isEqualTo(sorted.reversed().subList(1, 3))
            assertThat(it["hasNextPage"].asBoolean()).isTrue()
            assertThat(it["totalCount"].asInt()).isEqualTo(4)
        }
        connection("(first: 0)").let {
            assertThat(it["nodes"].size()).isZero()
            assertThat(it["hasNextPage"].asBoolean()).isTrue()
        }
    }

    @Test
    fun `a wishlist reads only its WishlistEntity row, product variants and wishlists are read only when selected`() {
        val user = registerUser()
        val id = createdId(user, listOf(registerProductVariant()))
        fun record(query: String, variables: Map<String, Any?>) = SqlStatementLog.record {
            assertThat(graphql(query, variables, employee)["errors"]).isNull()
        }
        val only = record("query(\$id: UUID!) { wishlist(id: \$id) { id name } }", mapOf("id" to id))
        assertThat(only).singleElement().satisfies({ assertThat(it).containsIgnoringCase("FROM \"WishlistEntity\"") })
        val withVariants = record("query(\$id: UUID!) { wishlist(id: \$id) { productVariants { nodes { id } } } }", mapOf("id" to id))
        assertThat(withVariants).hasSize(2)
        assertThat(withVariants[1]).contains("INNER JOIN WishlistToProductVariantEntity j")
        val user1 = record(
            "query(\$r: [_Any!]!) { _entities(representations: \$r) { ... on User { id } } }",
            mapOf("r" to listOf(mapOf("__typename" to "User", "id" to "$user")))
        )
        assertThat(user1).noneMatch { it.contains("WishlistEntity", ignoreCase = true) }
    }

    @Test
    fun `updateWishlist and deleteWishlist`() {
        val user = registerUser()
        val (v1, v2, v3) = Triple(registerProductVariant(), registerProductVariant(), registerProductVariant())
        val created = create(user, listOf(v1, v2), "old")["data"]["createWishlist"]
        val id = created["id"].asString()
        val unknown = UUID.randomUUID()
        assertThat(errorMessage(update("$unknown", null, "x", employee))).endsWith(": null")
        assertThat(errorMessage(update(id, null, "x", buyer(UUID.randomUUID())))).contains("is not an employee or admin")
        assertThat(errorMessage(update(id, listOf(v3, unknown), null, buyer(user)))).endsWith("Product variant with id $unknown does not exist")
        assertThat(variantsOf(id)).containsExactlyInAnyOrder("$v1", "$v2")

        // nothing given: nothing changes
        val noop = update(id, null, null, buyer(user))["data"]["updateWishlist"]
        assertThat(noop["lastUpdatedAt"]).isEqualTo(created["lastUpdatedAt"])
        Thread.sleep(5)
        val renamed = update(id, null, "new", buyer(user))["data"]["updateWishlist"]
        assertThat(renamed["name"].asString()).isEqualTo("new")
        assertThat(OffsetDateTime.parse(renamed["lastUpdatedAt"].asString()))
            .isAfter(OffsetDateTime.parse(created["lastUpdatedAt"].asString()))
        assertThat(renamed["createdAt"]).isEqualTo(created["createdAt"])
        // replace the variants: lock the wishlist, validate, one DELETE, one INSERT, UPDATE
        val statements = statementsOf {
            val replaced = update(id, listOf(v3, v2, v3), null, employee)["data"]["updateWishlist"]
            assertThat(replaced["name"].asString()).isEqualTo("new")
        }.filterNot { "INNER JOIN" in it }
        assertThat(statements).hasSize(5)
        assertThat(statements[0]).contains("FOR UPDATE")
        assertThat(statements[2]).startsWith("DELETE FROM WishlistToProductVariantEntity")
        assertThat(statements[3]).startsWith("INSERT INTO WishlistToProductVariantEntity")
        assertThat(statements[4]).startsWithIgnoringCase("UPDATE \"WishlistEntity\"")
        assertThat(variantsOf(id)).containsExactlyInAnyOrder("$v2", "$v3")
        assertThat(update(id, emptyList(), null, employee)["data"]["updateWishlist"]["productVariants"]["totalCount"].asInt()).isZero()

        assertThat(errorMessage(graphql(DELETE, mapOf("id" to id), buyer(UUID.randomUUID())))).contains("is not an employee or admin")
        assertThat(graphql(DELETE, mapOf("id" to id), buyer(user))["data"]["deleteWishlist"].asBoolean()).isTrue()
        assertThat(errorMessage(graphql(DELETE, mapOf("id" to id), buyer(user)))).endsWith(": null")
        assertThat(errorMessage(wishlist(id))).endsWith(": null")
    }

    companion object {
        const val WISHLIST_FIELDS = "id name createdAt lastUpdatedAt user { id } productVariants { totalCount nodes { id } }"
        const val CREATE = "mutation(\$i: CreateWishlistInput!) { createWishlist(input: \$i) { $WISHLIST_FIELDS } }"
        const val UPDATE = "mutation(\$i: UpdateWishlistInput!) { updateWishlist(input: \$i) { $WISHLIST_FIELDS } }"
        const val DELETE = "mutation(\$id: UUID!) { deleteWishlist(id: \$id) }"
        const val WISHLIST = "query(\$id: UUID!) { wishlist(id: \$id) { $WISHLIST_FIELDS } }"
        const val LIST = "query(\$r: [_Any!]!) { _entities(representations: \$r) { ... on User { id wishlists%s { " +
            "totalCount hasNextPage nodes { id name } } } } }"
    }
}
