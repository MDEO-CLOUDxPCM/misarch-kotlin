package org.misarch.wishlist

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
 * Behaviour of the wishlist port against Postgres (Testcontainers) and the fake Dapr sidecar, compared with the
 * responses of the original (recorded from `ghcr.io/misarch/wishlist:main`, see docs/specs/wishlist.md).
 */
@Import(SqlStatementLog::class)
class WishlistIntegrationTest : MisarchIntegrationTest() {

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

    private fun event(topic: String, data: String, contentType: String = "application/cloudevents+json"): HttpResult =
        post(
            "/on-topic-event",
            """{"id":"${UUID.randomUUID()}","source":"test","type":"com.dapr.event.sent","specversion":"1.0",
               "datacontenttype":"application/json","topic":"$topic","pubsubname":"pubsub","data":$data}""",
            contentType
        )

    private fun newUser(): UUID = UUID.randomUUID().also {
        assertThat(event("user/user/created", """{"id":"$it","username":"u"}""").body).isEqualTo("""{"status":0}""")
    }

    private fun newProductVariant(): UUID = UUID.randomUUID().also {
        assertThat(event("catalog/product-variant/created", """{"id":"$it"}""").status).isEqualTo(200)
    }

    private fun buyer(id: UUID) = authorizedUserHeader(id, "buyer")

    private fun JsonNode.items(): List<JsonNode> = iterator().asSequence().toList()

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

    private fun create(user: UUID, variants: List<UUID>, name: String, header: String? = buyer(user)): HttpResult =
        gql(CREATE, mapOf("i" to mapOf("userId" to "$user", "productVariantIds" to variants.map { "$it" }, "name" to name)), header)

    private fun createdId(result: HttpResult): String = result.json["data"]["createWishlist"]["id"].asString()

    private fun list(user: UUID, args: String = "", header: String? = buyer(user), fields: String = "id name createdAt lastUpdatedAt"): HttpResult =
        gql(LIST.format(args, fields), mapOf("r" to listOf(mapOf("__typename" to "User", "id" to "$user"))), header)

    private val timestamp = Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(\.\d{3})?\+00:00""")

    // ---------------------------------------------------------------- HTTP surface and events

    @Test
    fun `dapr subscribe, health and GraphiQL are like the original`() {
        val body = webTestClient.get().uri("/dapr/subscribe").exchange().expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!
        assertThat(body).isEqualTo(File("../../reference/dapr-subscribe/wishlist.json").readText().trim())
        assertThat(webTestClient.get().uri("/health").exchange().expectStatus().isOk.expectBody().returnResult().responseBody).isNull()
        assertThat(webTestClient.get().uri("/").exchange().expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody)
            .contains("<title>GraphiQL IDE</title>")
        val result = gql("{ __typename }")
        assertThat(result.contentType).isEqualTo("application/graphql-response+json")
        assertThat(result.body).isEqualTo("""{"data":{"__typename":"Query"}}""")
        assertThat(post("/", """[{"query":"{ __typename }"}]""").status).isEqualTo(400)
    }

    @Test
    fun `topic events - success, idempotent duplicates (FIXED), unknown topics and malformed bodies`() {
        val user = newUser()
        val variant = newProductVariant()
        event("user/user/created", """{"id":"${UUID.randomUUID()}"}""").let {
            assertThat(it.status).isEqualTo(200)
            assertThat(it.contentType).isEqualTo("application/json")
            assertThat(it.body).isEqualTo("""{"status":0}""")
        }
        assertThat(event("user/user/created", """{"id":"$user"}""").status).isEqualTo(200)
        assertThat(event("catalog/product-variant/created", """{"id":"$variant"}""").status).isEqualTo(200)
        assertThat(event("x/y", """{"id":"${UUID.randomUUID()}"}""").status).isEqualTo(500)
        assertThat(event("user/user/created", """{"x":1}""").status).isEqualTo(422)
        assertThat(event("user/user/created", """{"id":"1-2-3-4-5"}""").status).isEqualTo(422)
        assertThat(event("user/user/created", """{"id":"$user"}""", "text/plain").status).isEqualTo(415)
        assertThat(post("/on-topic-event", "{", "application/json").status).isEqualTo(400)
        assertThat(dapr.published).isEmpty()
    }

    // ---------------------------------------------------------------- createWishlist

    @Test
    fun `createWishlist - authorization, validation order, messages and result`() {
        val user = newUser()
        val other = UUID.randomUUID()
        val unknown = UUID.randomUUID()
        val admin = authorizedUserHeader(other, "admin")
        val p = (1..3).map { newProductVariant() }
        assertThat(create(user, p, "a", header = null).body).isEqualTo(
            error("Authentication failed. Authorized-User header is not set or could not be parsed.", listOf("createWishlist"), 1, 38)
        )
        assertThat(create(user, p, "a", header = buyer(other)).body).isEqualTo(
            error("Authentication failed for user of UUID: `$other`. Operation not permitted.", listOf("createWishlist"), 1, 38)
        )
        assertThat(create(user, listOf(unknown), "a").body).isEqualTo(
            error("Product variant with the UUID: `$unknown` is not present in the system.", listOf("createWishlist"), 1, 38)
        )
        assertThat(create(unknown, p, "a", header = admin).body).isEqualTo(
            error("misarch_wishlist::graphql::model::user::User with UUID: `$unknown` not found.", listOf("createWishlist"), 1, 38)
        )
        assertThat(create(unknown, emptyList(), "a", header = admin).json["errors"][0]["message"].asString())
            .isEqualTo("misarch_wishlist::graphql::model::user::User with UUID: `$unknown` not found.")

        val created = create(user, p + p.first(), "beta").json["data"]["createWishlist"]
        assertThat(created["name"].asString()).isEqualTo("beta")
        assertThat(created["user"]["id"].asString()).isEqualTo("$user")
        assertThat(created["createdAt"].asString()).matches(timestamp.pattern).isEqualTo(created["lastUpdatedAt"].asString())
        assertThat(created["productVariants"]["totalCount"].asInt()).isEqualTo(3)
        assertThat(created["productVariants"]["hasNextPage"].asBoolean()).isFalse()
        assertThat(created["productVariants"]["nodes"].items().map { it["id"].asString() })
            .isEqualTo(p.sortedWith(RustUuid.ORDER).map { "$it" })
        // empty name and empty variant list are accepted
        assertThat(create(user, emptyList(), "").json["data"]["createWishlist"]["productVariants"]["totalCount"].asInt()).isZero()
        assertThat(list(user).json["data"]["_entities"][0]["wishlists"]["totalCount"].asInt()).isEqualTo(2)
    }

    @Test
    fun `createWishlist and updateWishlist validate every product variant (FIXED Q1)`() {
        val user = newUser()
        val known = newProductVariant()
        val unknown = UUID.randomUUID()
        val unknown2 = UUID.randomUUID()
        repeat(10) {
            assertThat(create(user, listOf(known, unknown), "w").json["errors"][0]["message"].asString())
                .isEqualTo("Product variant with the UUID: `$unknown` is not present in the system.")
        }
        // the first unknown id in input order is reported
        assertThat(create(user, listOf(unknown2, known, unknown), "w").json["errors"][0]["message"].asString())
            .isEqualTo("Product variant with the UUID: `$unknown2` is not present in the system.")
        assertThat(list(user).json["data"]["_entities"][0]["wishlists"]["totalCount"].asInt()).isZero()
        val id = create(user, listOf(known), "w").json["data"]["createWishlist"]["id"].asString()
        val update = gql(UPDATE, mapOf("i" to mapOf("id" to id, "productVariantIds" to listOf("$known", "$unknown"))), buyer(user))
        assertThat(update.json["errors"][0]["message"].asString())
            .isEqualTo("Product variant with the UUID: `$unknown` is not present in the system.")
    }

    // ---------------------------------------------------------------- wishlist / entities

    @Test
    fun `wishlist query and Wishlist entity check existence before authorization`() {
        val user = newUser()
        val other = UUID.randomUUID()
        val unknown = UUID.randomUUID()
        val p = listOf(newProductVariant(), newProductVariant())
        val id = createdId(create(user, p, "mine"))

        assertThat(gql(WISHLIST, mapOf("id" to "$unknown")).body).isEqualTo(
            error("misarch_wishlist::graphql::model::wishlist::Wishlist with UUID: `$unknown` not found.", listOf("wishlist"), 1, 21)
        )
        assertThat(gql(WISHLIST, mapOf("id" to id), buyer(other)).body).isEqualTo(
            error("Authentication failed for user of UUID: `$other`. Operation not permitted.", listOf("wishlist"), 1, 21)
        )
        val owner = gql(WISHLIST, mapOf("id" to id), buyer(user)).json["data"]["wishlist"]
        assertThat(owner["id"].asString()).isEqualTo(id)
        assertThat(gql(WISHLIST, mapOf("id" to id), authorizedUserHeader(other, "employee")).json["data"]["wishlist"]).isEqualTo(owner)

        val nested = gql("query(\$id: UUID!) { wishlist(id: \$id) { user { id wishlists(first: 1) { totalCount nodes { id } } } } }",
            mapOf("id" to id), buyer(user)).json["data"]["wishlist"]["user"]
        assertThat(nested["wishlists"]["totalCount"].asInt()).isEqualTo(1)
        assertThat(nested["wishlists"]["nodes"][0]["id"].asString()).isEqualTo(id)

        val entityQuery = "query(\$r: [_Any!]!) { _entities(representations: \$r) { ... on Wishlist { id name } } }"
        val representation = mapOf("r" to listOf(mapOf("__typename" to "Wishlist", "id" to id)))
        assertThat(gql(entityQuery, representation, buyer(other)).body).isEqualTo(
            error("Authentication failed for user of UUID: `$other`. Operation not permitted.", listOf("_entities"), 1, 23)
        )
        assertThat(gql(entityQuery, representation, buyer(user)).body)
            .isEqualTo("""{"data":{"_entities":[{"id":"$id","name":"mine"}]}}""")

        val page = gql("query(\$id: UUID!) { wishlist(id: \$id) { productVariants(first: 1, skip: 1, orderBy: {direction: DESC, field: ID}) { totalCount hasNextPage nodes { id } } } }",
            mapOf("id" to id), buyer(user)).json["data"]["wishlist"]["productVariants"]
        assertThat(page["totalCount"].asInt()).isEqualTo(2)
        assertThat(page["hasNextPage"].asBoolean()).isFalse()
        assertThat(page["nodes"][0]["id"].asString()).isEqualTo("${p.sortedWith(RustUuid.ORDER).first()}")
    }

    @Test
    fun `entities User - no authorization for the user, but User wishlists authorizes`() {
        val user = newUser()
        val unknown = UUID.randomUUID()
        assertThat(gql("query(\$r: [_Any!]!) { _entities(representations: \$r) { ... on User { id } } }",
            mapOf("r" to listOf(mapOf("__typename" to "User", "id" to "$user")))).body)
            .isEqualTo("""{"data":{"_entities":[{"id":"$user"}]}}""")
        assertThat(list(unknown, header = authorizedUserHeader(user, "admin")).body).isEqualTo(
            error("misarch_wishlist::graphql::model::user::User with UUID: `$unknown` not found.", listOf("_entities"), 1, 23)
        )
        // errors inside the entity: data null, path without list index (async-graphql)
        assertThat(list(user, header = null).body).isEqualTo(
            error("Authentication failed. Authorized-User header is not set or could not be parsed.", listOf("_entities", "wishlists"), 1, 73)
        )
        assertThat(list(user, header = buyer(unknown)).body).isEqualTo(
            error("Authentication failed for user of UUID: `$unknown`. Operation not permitted.", listOf("_entities", "wishlists"), 1, 73)
        )
        assertThat(list(user, "(first: -1)").body).isEqualTo(
            error("Failed to parse \"Int\": Invalid number", listOf("_entities", "wishlists"), 1, 90)
        )
        list(user).json["data"]["_entities"][0]["wishlists"].let {
            assertThat(it["totalCount"].asInt()).isZero()
            assertThat(it["hasNextPage"].asBoolean()).isFalse()
            assertThat(it["nodes"].size()).isZero()
        }
        assertThat(gql("query(\$r: [_Any!]!) { _entities(representations: \$r) { __typename } }",
            mapOf("r" to listOf(mapOf("__typename" to "ProductVariant", "id" to "$user")))).body)
            .isEqualTo(error("Entity not found.", null, 1, 23))
    }

    // ---------------------------------------------------------------- User.wishlists ordering and pagination

    @Test
    fun `User wishlists - order fields with _id DESC tie-breaker and binary name order`() {
        val user = newUser()
        val variant = newProductVariant()
        val names = listOf("beta", "alpha", "Zeta", "\u00e4pfel", "beta", "", "alpha", "omega")
        names.forEach { createdId(create(user, listOf(variant), it)) }
        val all = list(user, "(first: 100)").json["data"]["_entities"][0]["wishlists"]["nodes"].items()
        assertThat(all).hasSize(8)

        data class W(val id: String, val name: String, val createdAt: OffsetDateTime, val lastUpdatedAt: OffsetDateTime)
        val wishlists = all.map {
            W(it["id"].asString(), it["name"].asString(), OffsetDateTime.parse(it["createdAt"].asString()), OffsetDateTime.parse(it["lastUpdatedAt"].asString()))
        }
        val idAsc = compareBy<W> { it.id } // lowercase hex = unsigned byte order
        val idDesc = idAsc.reversed()
        val expected = mapOf(
            "ID ASC" to wishlists.sortedWith(idAsc),
            "ID DESC" to wishlists.sortedWith(idDesc),
            "USER_ID ASC" to wishlists.sortedWith(idDesc),
            "USER_ID DESC" to wishlists.sortedWith(idDesc),
            "NAME ASC" to wishlists.sortedWith(compareBy<W> { it.name }.then(idDesc)),
            "NAME DESC" to wishlists.sortedWith(compareByDescending<W> { it.name }.then(idDesc)),
            "CREATED_AT ASC" to wishlists.sortedWith(compareBy<W> { it.createdAt }.then(idDesc)),
            "CREATED_AT DESC" to wishlists.sortedWith(compareByDescending<W> { it.createdAt }.then(idDesc)),
            "LAST_UPDATED_AT ASC" to wishlists.sortedWith(compareBy<W> { it.lastUpdatedAt }.then(idDesc)),
            "LAST_UPDATED_AT DESC" to wishlists.sortedWith(compareByDescending<W> { it.lastUpdatedAt }.then(idDesc)),
        )
        for ((order, sorted) in expected) {
            val (field, direction) = order.split(" ")
            val nodes = list(user, "(orderBy: {field: $field, direction: $direction})").json["data"]["_entities"][0]["wishlists"]["nodes"].items()
            assertThat(nodes.map { it["id"].asString() }).describedAs(order).isEqualTo(sorted.map { it.id })
        }
        // byte order, as in the original: "" < "Zeta" < "alpha" < ... < "omega" < "äpfel"
        assertThat(expected.getValue("NAME ASC").map { it.name }).containsExactly("", "Zeta", "alpha", "alpha", "beta", "beta", "omega", "\u00e4pfel")
        // default: ID ASC
        assertThat(list(user).json["data"]["_entities"][0]["wishlists"]["nodes"].items().map { it["id"].asString() })
            .isEqualTo(expected.getValue("ID ASC").map { it.id })
    }

    @Test
    fun `User wishlists - pagination of mongodb-cursor-pagination (FIXED skip off-by-one Q3)`() {
        val user = newUser()
        val variant = newProductVariant()
        listOf("beta", "alpha", "Zeta", "\u00e4pfel", "beta", "", "alpha", "omega").forEach { create(user, listOf(variant), it) }
        val byName = listOf("", "Zeta", "alpha", "alpha", "beta", "beta", "omega", "\u00e4pfel")
        // (first, skip) -> (names, hasNextPage) as the original answered for these 8 wishlists
        val cases = listOf(
            Triple(null, null, byName to false),
            Triple(0, null, emptyList<String>() to true),
            Triple(3, null, byName.subList(0, 3) to true),
            Triple(3, 0, byName.subList(0, 3) to true),
            Triple(3, 2, byName.subList(2, 5) to true),
            Triple(3, 4, byName.subList(4, 7) to true), // original: first + 1 nodes, false
            Triple(2, 5, byName.subList(5, 7) to true), // original: first + 1 nodes, false
            Triple(4, 4, byName.subList(4, 8) to false),
            Triple(7, 1, byName.subList(1, 8) to false),
            Triple(3, 7, byName.subList(7, 8) to false),
            Triple(3, 8, emptyList<String>() to false),
            Triple(3, 20, emptyList<String>() to false),
            Triple(0, 7, emptyList<String>() to true), // original: first + 1 nodes, false
        )
        for ((first, skip, expected) in cases) {
            val args = listOfNotNull(first?.let { "first: $it" }, skip?.let { "skip: $it" }, "orderBy: {field: NAME}").joinToString(", ")
            val connection = list(user, "($args)", fields = "name").json["data"]["_entities"][0]["wishlists"]
            assertThat(connection["totalCount"].asInt()).isEqualTo(8)
            assertThat(connection["nodes"].items().map { it["name"].asString() }).describedAs("first=$first skip=$skip").isEqualTo(expected.first)
            assertThat(connection["hasNextPage"].asBoolean()).describedAs("first=$first skip=$skip").isEqualTo(expected.second)
        }
    }

    @Test
    fun `User wishlists - default page size is 25`() {
        val user = newUser()
        repeat(27) { create(user, emptyList(), "w$it") }
        val connection = list(user, fields = "id").json["data"]["_entities"][0]["wishlists"]
        assertThat(connection["totalCount"].asInt()).isEqualTo(27)
        assertThat(connection["nodes"].size()).isEqualTo(25)
        assertThat(connection["hasNextPage"].asBoolean()).isTrue()
    }

    @Test
    fun `a wishlist reads only its WishlistEntity row, product variants and wishlists are read only when selected`() {
        val user = newUser()
        val p = (1..3).map { newProductVariant() }
        val id = createdId(create(user, p, "w"))
        fun record(query: String, variables: Map<String, Any?>): List<String> = SqlStatementLog.record {
            assertThat(gql(query, variables, buyer(user)).json["errors"]).isNull()
        }
        fun links(statements: List<String>) = statements.filter { "WishlistToProductVariantEntity" in it }

        val rowOnly = record("query(\$id: UUID!) { wishlist(id: \$id) { id name user { id } } }", mapOf("id" to id))
        assertThat(rowOnly).singleElement().satisfies({ assertThat(it).startsWith("SELECT * FROM WishlistEntity") })

        val nodesOnly = record("query(\$id: UUID!) { wishlist(id: \$id) { productVariants { nodes { id } } } }", mapOf("id" to id))
        assertThat(nodesOnly).hasSize(2)
        assertThat(links(nodesOnly)).singleElement().satisfies({ assertThat(it).startsWith("SELECT t.* FROM WishlistToProductVariantEntity t") })
        val all = record("query(\$id: UUID!) { wishlist(id: \$id) { productVariants(first: 1) { totalCount hasNextPage nodes { id } } } }", mapOf("id" to id))
        assertThat(links(all)).hasSize(3)
        val sorted = p.sortedWith(RustUuid.ORDER).map { "$it" }
        for ((args, expected) in listOf(
            "(first: 1)" to Triple(sorted.subList(0, 1), true, 3),
            "(first: 2, skip: 1)" to Triple(sorted.subList(1, 3), false, 3),
            "(first: 0, skip: 2)" to Triple(emptyList(), true, 3),
            "(skip: 1, orderBy: {direction: DESC})" to Triple(sorted.reversed().subList(1, 3), false, 3),
            "(first: 5, skip: 9)" to Triple(emptyList(), false, 3),
        )) {
            val c = gql("query(\$id: UUID!) { wishlist(id: \$id) { productVariants$args { totalCount hasNextPage nodes { id } } } }",
                mapOf("id" to id), buyer(user)).json["data"]["wishlist"]["productVariants"]
            assertThat(c["nodes"].items().map { it["id"].asString() }).describedAs(args).isEqualTo(expected.first)
            assertThat(c["hasNextPage"].asBoolean()).describedAs(args).isEqualTo(expected.second)
            assertThat(c["totalCount"].asInt()).isEqualTo(expected.third)
        }

        // User.wishlists: the user replica check, then only the selected statements (no count without totalCount)
        val representations = mapOf("r" to listOf(mapOf("__typename" to "User", "id" to "$user")))
        val listNodes = record("query(\$r: [_Any!]!) { _entities(representations: \$r) { ... on User { wishlists { nodes { id } } } } }", representations)
        assertThat(listNodes).hasSize(2)
        assertThat(listNodes.filter { "FROM WishlistEntity" in it }).singleElement()
            .satisfies({ assertThat(it).startsWith("SELECT t.* FROM WishlistEntity t") })
        assertThat(links(listNodes)).isEmpty()
        val listAll = record(LIST.format("", "id"), representations)
        assertThat(listAll.filter { "FROM WishlistEntity" in it }).hasSize(3)
    }

    // ---------------------------------------------------------------- update / delete

    @Test
    fun `updateWishlist and deleteWishlist`() {
        val user = newUser()
        val other = UUID.randomUUID()
        val unknown = UUID.randomUUID()
        val p = listOf(newProductVariant(), newProductVariant())
        val id = createdId(create(user, p, "beta"))
        val before = gql(WISHLIST, mapOf("id" to id), buyer(user)).json["data"]["wishlist"]

        assertThat(gql(UPDATE, mapOf("i" to mapOf("id" to "$unknown", "name" to "n"))).body).isEqualTo(
            error("misarch_wishlist::graphql::model::wishlist::Wishlist with UUID: `$unknown` not found.", listOf("updateWishlist"), 1, 38)
        )
        assertThat(gql(UPDATE, mapOf("i" to mapOf("id" to id, "name" to "n")), buyer(other)).body).isEqualTo(
            error("Authentication failed for user of UUID: `$other`. Operation not permitted.", listOf("updateWishlist"), 1, 38)
        )
        val nothing = gql(UPDATE, mapOf("i" to mapOf("id" to id)), buyer(user)).json["data"]["updateWishlist"]
        assertThat(nothing).isEqualTo(before)

        Thread.sleep(5)
        val renamed = gql(UPDATE, mapOf("i" to mapOf("id" to id, "name" to "renamed")), buyer(user)).json["data"]["updateWishlist"]
        assertThat(renamed["name"].asString()).isEqualTo("renamed")
        assertThat(renamed["createdAt"]).isEqualTo(before["createdAt"])
        assertThat(OffsetDateTime.parse(renamed["lastUpdatedAt"].asString())).isAfter(OffsetDateTime.parse(before["lastUpdatedAt"].asString()))
        assertThat(renamed["productVariants"]).isEqualTo(before["productVariants"])

        // unknown variant: nothing is written, not even the name
        assertThat(gql(UPDATE, mapOf("i" to mapOf("id" to id, "productVariantIds" to listOf("$unknown"), "name" to "x")), buyer(user)).body)
            .isEqualTo(error("Product variant with the UUID: `$unknown` is not present in the system.", listOf("updateWishlist"), 1, 38))
        val variants = gql(UPDATE, mapOf("i" to mapOf("id" to id, "productVariantIds" to listOf("${p[1]}", "${p[1]}"))), buyer(user))
            .json["data"]["updateWishlist"]
        assertThat(variants["name"].asString()).isEqualTo("renamed")
        assertThat(variants["productVariants"]["nodes"].items().map { it["id"].asString() }).containsExactly("${p[1]}")
        val empty = gql(UPDATE, mapOf("i" to mapOf("id" to id, "productVariantIds" to emptyList<String>())), authorizedUserHeader(other, "admin"))
            .json["data"]["updateWishlist"]
        assertThat(empty["productVariants"]["totalCount"].asInt()).isZero()

        assertThat(gql(DELETE, mapOf("id" to "$unknown"), buyer(user)).body).isEqualTo(
            error("misarch_wishlist::graphql::model::wishlist::Wishlist with UUID: `$unknown` not found.", listOf("deleteWishlist"), 1, 24)
        )
        assertThat(gql(DELETE, mapOf("id" to id), buyer(other)).body).isEqualTo(
            error("Authentication failed for user of UUID: `$other`. Operation not permitted.", listOf("deleteWishlist"), 1, 24)
        )
        assertThat(gql(DELETE, mapOf("id" to id), buyer(user)).body).isEqualTo("""{"data":{"deleteWishlist":true}}""")
        assertThat(gql(DELETE, mapOf("id" to id), buyer(user)).json["errors"][0]["message"].asString())
            .isEqualTo("misarch_wishlist::graphql::model::wishlist::Wishlist with UUID: `$id` not found.")
        assertThat(dapr.published).isEmpty()
    }

    @Test
    fun `a failing mutation stops later mutation fields`() {
        val user = newUser()
        val unknown = UUID.randomUUID()
        val result = gql(
            """mutation { a: deleteWishlist(id: "$unknown") b: createWishlist(input: {userId: "$user", productVariantIds: [], name: "x"}) { id } }""",
            user = buyer(user)
        )
        assertThat(result.body).isEqualTo(
            error("misarch_wishlist::graphql::model::wishlist::Wishlist with UUID: `$unknown` not found.", listOf("a"), 1, 12)
        )
        assertThat(list(user).json["data"]["_entities"][0]["wishlists"]["totalCount"].asInt()).isZero()
    }

    companion object {
        const val WL = "id name createdAt lastUpdatedAt user { id } productVariants { totalCount hasNextPage nodes { id } }"
        const val CREATE = "mutation(\$i: CreateWishlistInput!) { createWishlist(input: \$i) { $WL } }"
        const val UPDATE = "mutation(\$i: UpdateWishlistInput!) { updateWishlist(input: \$i) { $WL } }"
        const val DELETE = "mutation(\$id: UUID!) { deleteWishlist(id: \$id) }"
        const val WISHLIST = "query(\$id: UUID!) { wishlist(id: \$id) { $WL } }"
        const val LIST = "query(\$r: [_Any!]!) { _entities(representations: \$r) { ... on User { id wishlists%s " +
            "{ totalCount hasNextPage nodes { %s } } } } }"
    }
}
