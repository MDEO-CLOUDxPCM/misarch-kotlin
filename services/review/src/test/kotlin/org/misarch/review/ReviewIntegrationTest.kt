package org.misarch.review

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.entry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.misarch.common.testing.MisarchIntegrationTest
import org.misarch.common.testing.SqlStatementLog
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitOneOrNull
import org.springframework.r2dbc.core.awaitRowsUpdated
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Behaviour of the review service against a real Postgres (Testcontainers) and a fake Dapr sidecar: event
 * subscriptions, mutations with authorization, queries, connections (paging, order, lazy statements), average
 * ratings and federation entities.
 */
@Import(SqlStatementLog::class)
class ReviewIntegrationTest : MisarchIntegrationTest() {

    override val graphqlPath: String = "/"

    @Autowired
    lateinit var db: DatabaseClient

    @BeforeEach
    fun cleanReviews() = runBlocking {
        db.sql("DELETE FROM ReviewEntity").fetch().awaitRowsUpdated()
        Unit
    }

    private val reviewFields = "id user { id } productVariant { id } body rating createdAt lastUpdatedAt isVisible"

    private fun admin() = authorizedUserHeader(UUID.randomUUID(), "admin")
    private fun buyer(id: UUID) = authorizedUserHeader(id, "buyer")

    private fun registerUser(): UUID = UUID.randomUUID().also {
        assertThat(deliverEvent("/subscription/user/user/created", "user/user/created", """{"id":"$it","username":"u"}"""))
            .isEqualTo(200)
    }

    private fun registerProductVariant(productId: UUID = UUID.randomUUID()): UUID = UUID.randomUUID().also {
        assertThat(
            deliverEvent(
                "/subscription/catalog/product-variant/created", "catalog/product-variant/created",
                """{"id":"$it","productId":"$productId","currentVersionId":"${UUID.randomUUID()}","isPubliclyVisible":true}"""
            )
        ).isEqualTo(200)
    }

    private fun createReview(user: UUID, pv: UUID, rating: String = "FIVE_STARS", extra: String = "", header: String? = buyer(user)) =
        graphql(
            """mutation { createReview(input: {userId: "$user", productVariantId: "$pv", body: "nice", rating: $rating $extra}) { $reviewFields } }""",
            user = header,
        )

    private fun error(result: JsonNode): String = result.path("errors").path(0).path("message").asString()

    /** Inserts a review row directly (controlled ids, ratings and timestamps). */
    private fun seed(
        id: UUID = UUID.randomUUID(), user: UUID = UUID.randomUUID(), pv: UUID = UUID.randomUUID(),
        product: UUID = UUID.randomUUID(), rating: String = "FIVE_STARS", visible: Boolean = true,
        created: Instant = Instant.parse("2026-01-01T00:00:00Z"), updated: Instant = created,
    ): UUID = runBlocking {
        db.sql(
            "INSERT INTO ReviewEntity (id, userId, productVariantId, productId, body, rating, createdAt, lastUpdatedAt, isVisible) " +
                "VALUES (:id, :user, :pv, :product, 'body', :rating, :created, :updated, :visible)"
        ).bind("id", id).bind("user", user).bind("pv", pv).bind("product", product).bind("rating", rating)
            .bind("created", created.atOffset(ZoneOffset.UTC)).bind("updated", updated.atOffset(ZoneOffset.UTC))
            .bind("visible", visible).fetch().awaitRowsUpdated()
        id
    }

    private fun storedRating(id: UUID): String? = runBlocking {
        db.sql("SELECT rating FROM ReviewEntity WHERE id = :id").bind("id", id)
            .map { row -> row.get("rating", String::class.java)!! }.awaitOneOrNull()
    }

    private fun ids(connection: JsonNode): List<String> = connection.path("nodes").toList().map { it.path("id").asString() }

    private fun uuid(i: Int): UUID = UUID.fromString("%08x-0000-4000-8000-000000000000".format(i))

    private fun entity(typename: String, id: UUID, selection: String): JsonNode =
        graphql("""{ _entities(representations: [{__typename: "$typename", id: "$id"}]) { ... on $typename { $selection } } }""")

    // ------------------------------------------------------------------ Dapr, health

    @Test
    fun `dapr subscribe lists the three topics`() {
        val body = webTestClient.get().uri("/dapr/subscribe").exchange()
            .expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!
        val subscriptions = mapper.readTree(body).iterator().asSequence()
            .associate { it["topic"].asString() to (it["pubsubName"].asString() to it["route"].asString()) }
        assertThat(subscriptions).containsOnly(
            entry("user/user/created", "pubsub" to "/subscription/user/user/created"),
            entry("catalog/product/created", "pubsub" to "/subscription/catalog/product/created"),
            entry("catalog/product-variant/created", "pubsub" to "/subscription/catalog/product-variant/created"),
        )
    }

    @Test
    fun `health endpoint is UP`() {
        webTestClient.get().uri("/health").exchange()
            .expectStatus().isOk
            .expectBody().json("""{"status":"UP"}""", org.springframework.test.json.JsonCompareMode.STRICT)
    }

    // ------------------------------------------------------------------ events

    @Test
    fun `replica events are stored and idempotent`() {
        val user = UUID.randomUUID()
        val product = UUID.randomUUID()
        repeat(2) {
            assertThat(deliverEvent("/subscription/user/user/created", "user/user/created", """{"id":"$user"}""")).isEqualTo(200)
            assertThat(deliverEvent("/subscription/catalog/product/created", "catalog/product/created", """{"id":"$product","internalName":"p"}"""))
                .isEqualTo(200)
        }
        assertThat(entity("User", user, "id")["data"]["_entities"][0]["id"].asString()).isEqualTo("$user")
        assertThat(entity("Product", product, "id")["data"]["_entities"][0]["id"].asString()).isEqualTo("$product")
        // a product variant event without its product is rejected
        assertThat(deliverEvent("/subscription/catalog/product-variant/created", "catalog/product-variant/created", """{"id":"${UUID.randomUUID()}"}"""))
            .isEqualTo(400)
    }

    @Test
    fun `product variant events are stored with their product and enable createReview`() {
        val user = registerUser()
        val product = UUID.randomUUID()
        val pv = registerProductVariant(product)
        assertThat(
            deliverEvent("/subscription/catalog/product-variant/created", "catalog/product-variant/created", """{"id":"$pv","productId":"${UUID.randomUUID()}"}""")
        ).isEqualTo(200)
        assertThat(entity("ProductVariant", pv, "id")["data"]["_entities"][0]["id"].asString()).isEqualTo("$pv")
        assertThat(createReview(user, pv)["errors"]).isNull()
        // the review belongs to the product of the first event
        assertThat(
            graphql("""{ reviews { nodes { productVariant { id } } } }""")["data"]["reviews"]["nodes"][0]["productVariant"]["id"].asString()
        ).isEqualTo("$pv")
        val storedProductId: UUID? = runBlocking {
            db.sql("SELECT productId FROM ReviewEntity").map { row -> row.get(0, UUID::class.java)!! }.awaitOneOrNull()
        }
        assertThat(storedProductId).isEqualTo(product)
    }

    // ------------------------------------------------------------------ mutations

    @Test
    fun `createReview - authorization and validation`() {
        val user = registerUser()
        val pv = registerProductVariant()
        assertThat(error(createReview(user, pv, header = null))).endsWith("Unauthorized access")
        assertThat(error(createReview(user, pv, header = buyer(UUID.randomUUID())))).contains("is not an employee or admin")
        val unknown = UUID.randomUUID()
        assertThat(error(createReview(user, unknown))).endsWith("Product variant with id $unknown does not exist")
        assertThat(error(createReview(unknown, pv, header = admin()))).endsWith("User with id $unknown does not exist")
        assertThat(graphql("{ reviews { totalCount } }")["data"]["reviews"]["totalCount"].asInt()).isZero()
    }

    @Test
    fun `createReview stores the review, one review per user and product variant`() {
        val user = registerUser()
        val pv = registerProductVariant()
        val review = createReview(user, pv, "FOUR_STARS")["data"]["createReview"]
        assertThat(review["user"]["id"].asString()).isEqualTo("$user")
        assertThat(review["productVariant"]["id"].asString()).isEqualTo("$pv")
        assertThat(review["body"].asString()).isEqualTo("nice")
        assertThat(review["rating"].asString()).isEqualTo("FOUR_STARS")
        assertThat(review["isVisible"].asBoolean()).isTrue()
        assertThat(review["createdAt"].asString()).isEqualTo(review["lastUpdatedAt"].asString()).endsWith("Z")
        assertThat(storedRating(UUID.fromString(review["id"].asString()))).isEqualTo("FOUR_STARS")
        assertThat(error(createReview(user, pv))).endsWith("User with id $user has already reviewed product variant with id $pv")
        // an employee writes for another user; isVisible can be set
        val other = registerUser()
        val hidden = createReview(other, pv, extra = "isVisible: false", header = admin())["data"]["createReview"]
        assertThat(hidden["isVisible"].asBoolean()).isFalse()
    }

    @Test
    fun `concurrent createReview of one user and product variant stores one review`() {
        val user = registerUser()
        val pv = registerProductVariant()
        val results = (1..4).map { CompletableFuture.supplyAsync { createReview(user, pv) } }.map { it.get() }
        assertThat(results.count { it["errors"] == null }).isEqualTo(1)
        assertThat(graphql("{ reviews { totalCount } }")["data"]["reviews"]["totalCount"].asInt()).isEqualTo(1)
    }

    @Test
    fun `updateReview and deleteReview - existence before authorization`() {
        val owner = UUID.randomUUID()
        val id = seed(user = owner)
        val unknown = UUID.randomUUID()
        assertThat(error(graphql("""mutation { updateReview(input: {id: "$unknown", body: "x"}) { id } }""", user = admin())))
            .endsWith(": null")
        assertThat(error(graphql("""mutation { updateReview(input: {id: "$id", body: "x"}) { id } }""", user = buyer(UUID.randomUUID()))))
            .contains("is not an employee or admin")
        assertThat(error(graphql("""mutation { deleteReview(id: "$id") }""")))
            .endsWith("Unauthorized access")
        assertThat(graphql("""mutation { updateReview(input: {id: "$id", body: "x"}) { body } }""", user = admin())["data"]["updateReview"]["body"].asString())
            .isEqualTo("x")
        assertThat(graphql("""mutation { deleteReview(id: "$id") }""", user = buyer(owner))["data"]["deleteReview"].asBoolean()).isTrue()
        assertThat(graphql("""{ review(id: "$id") { id } }""")["data"]["review"].isNull).isTrue()
        assertThat(error(graphql("""mutation { deleteReview(id: "$id") }""", user = buyer(owner)))).endsWith(": null")
    }

    @Test
    fun `updateReview - partial updates and no-op`() {
        val owner = UUID.randomUUID()
        val id = seed(user = owner, rating = "TWO_STARS", created = Instant.parse("2026-01-01T10:00:00Z"))
        val noop = graphql(
            """mutation { updateReview(input: {id: "$id"}) { lastUpdatedAt body } }""", user = buyer(owner)
        )["data"]["updateReview"]
        assertThat(noop["lastUpdatedAt"].asString()).isEqualTo("2026-01-01T10:00:00.000Z")
        val updated = graphql(
            """mutation { updateReview(input: {id: "$id", body: "new", rating: FOUR_STARS, isVisible: false}) { body rating isVisible createdAt lastUpdatedAt } }""",
            user = buyer(owner)
        )["data"]["updateReview"]
        assertThat(updated["body"].asString()).isEqualTo("new")
        assertThat(updated["rating"].asString()).isEqualTo("FOUR_STARS")
        assertThat(updated["isVisible"].asBoolean()).isFalse()
        assertThat(updated["createdAt"].asString()).isEqualTo("2026-01-01T10:00:00.000Z")
        assertThat(updated["lastUpdatedAt"].asString()).isNotEqualTo("2026-01-01T10:00:00.000Z")
        assertThat(storedRating(id)).isEqualTo("FOUR_STARS")
        assertThat(graphql("""{ review(id: "$id") { rating } }""")["data"]["review"]["rating"].asString()).isEqualTo("FOUR_STARS")
    }

    // ------------------------------------------------------------------ queries and connections

    @Test
    fun `review by id, unknown id is null`() {
        val id = seed(rating = "THREE_STARS")
        assertThat(graphql("""{ review(id: "$id") { id rating } }""")["data"]["review"]["rating"].asString()).isEqualTo("THREE_STARS")
        assertThat(graphql("""{ review(id: "${UUID.randomUUID()}") { id } }""")["data"]["review"].isNull).isTrue()
    }

    @Test
    fun `reviews connection - default page size 25, first, skip, hasNextPage`() {
        (1..30).forEach { seed(id = uuid(it)) }
        val all = graphql("{ reviews { totalCount hasNextPage nodes { id } } }")["data"]["reviews"]
        assertThat(all["totalCount"].asInt()).isEqualTo(30)
        assertThat(ids(all)).isEqualTo((1..25).map { uuid(it).toString() })
        assertThat(all["hasNextPage"].asBoolean()).isTrue()
        val page = graphql("{ reviews(first: 3, skip: 26) { hasNextPage nodes { id } } }")["data"]["reviews"]
        assertThat(ids(page)).isEqualTo((27..29).map { uuid(it).toString() })
        assertThat(page["hasNextPage"].asBoolean()).isTrue()
        val last = graphql("{ reviews(first: 4, skip: 26) { hasNextPage nodes { id } } }")["data"]["reviews"]
        assertThat(ids(last)).hasSize(4)
        assertThat(last["hasNextPage"].asBoolean()).isFalse()
        val empty = graphql("{ reviews(first: 0) { hasNextPage nodes { id } } }")["data"]["reviews"]
        assertThat(empty["nodes"].size()).isZero()
        assertThat(empty["hasNextPage"].asBoolean()).isTrue()
        assertThat(error(graphql("{ reviews(skip: -1) { nodes { id } } }"))).endsWith("Offset must not be negative.")
        assertThat(error(graphql("{ reviews(first: -1) { nodes { id } } }"))).endsWith("must not be negative.")
    }

    @Test
    fun `connection fields are separate statements, each run only when selected`() {
        seed()
        fun reviewStatements(query: String) = SqlStatementLog.record { assertThat(graphql(query)["errors"]).isNull() }
            .filter { it.contains("ReviewEntity", ignoreCase = true) }
        assertThat(reviewStatements("{ reviews { totalCount } }")).singleElement()
            .satisfies({ assertThat(it).startsWith("SELECT COUNT(t.id)") })
        assertThat(reviewStatements("{ reviews { nodes { id } } }")).singleElement()
            .satisfies({ assertThat(it).startsWith("SELECT t.*") })
        assertThat(reviewStatements("{ reviews { totalCount hasNextPage nodes { id } } }")).hasSize(3)
    }

    @Test
    fun `order fields - rating by stars, ties by id descending`() {
        val user = UUID.randomUUID()
        seed(id = uuid(1), user = user, rating = "FIVE_STARS", created = Instant.parse("2026-01-03T00:00:00Z"))
        seed(id = uuid(2), user = user, rating = "ONE_STARS", created = Instant.parse("2026-01-01T00:00:00Z"))
        seed(id = uuid(3), user = user, rating = "FOUR_STARS", created = Instant.parse("2026-01-02T00:00:00Z"))
        seed(id = uuid(4), user = user, rating = "FIVE_STARS", created = Instant.parse("2026-01-02T00:00:00Z"))
        fun order(orderBy: String) = ids(graphql("{ reviews(orderBy: $orderBy) { nodes { id } } }")["data"]["reviews"])
            .map { UUID.fromString(it) }
        assertThat(order("{field: RATING}")).containsExactly(uuid(2), uuid(3), uuid(4), uuid(1))
        assertThat(order("{field: RATING, direction: DESC}")).containsExactly(uuid(4), uuid(1), uuid(3), uuid(2))
        assertThat(order("{field: CREATED_AT}")).containsExactly(uuid(2), uuid(4), uuid(3), uuid(1))
        assertThat(order("{field: USER_ID}")).containsExactly(uuid(4), uuid(3), uuid(2), uuid(1))
        assertThat(order("{direction: DESC}")).containsExactly(uuid(4), uuid(3), uuid(2), uuid(1))
        assertThat(order("{field: PRODUCT_VARIANT}")).hasSize(4)
    }

    @Test
    fun `nested connections filter by user, product variant and product`() {
        val user = UUID.randomUUID()
        val pv = UUID.randomUUID()
        val product = UUID.randomUUID()
        seed(id = uuid(1), user = user, pv = pv, product = product)
        seed(id = uuid(2), user = user)
        seed(id = uuid(3), pv = pv, product = product)
        seed(id = uuid(4), product = product)
        fun reviewsOf(typename: String, id: UUID): List<UUID> {
            val result = entity(typename, id, "reviews { nodes { id } }")
            return ids(result["data"]["_entities"][0]["reviews"]).map { UUID.fromString(it) }
        }
        runBlocking {
            db.sql("INSERT INTO UserEntity (id) VALUES (:id)").bind("id", user).fetch().awaitRowsUpdated()
            db.sql("INSERT INTO ProductEntity (id) VALUES (:id)").bind("id", product).fetch().awaitRowsUpdated()
            db.sql("INSERT INTO ProductVariantEntity (id, productId) VALUES (:id, :p)").bind("id", pv).bind("p", product)
                .fetch().awaitRowsUpdated()
        }
        assertThat(reviewsOf("User", user)).containsExactly(uuid(1), uuid(2))
        assertThat(reviewsOf("ProductVariant", pv)).containsExactly(uuid(1), uuid(3))
        assertThat(reviewsOf("Product", product)).containsExactly(uuid(1), uuid(3), uuid(4))
    }

    @Test
    fun `averageRating over all visible reviews`() {
        val product = UUID.randomUUID()
        val pv = registerProductVariant(product)
        assertThat(deliverEvent("/subscription/catalog/product/created", "catalog/product/created", """{"id":"$product"}""")).isEqualTo(200)
        assertThat(entity("ProductVariant", pv, "averageRating")["data"]["_entities"][0]["averageRating"].isNull).isTrue()
        seed(id = uuid(1), pv = pv, product = product, rating = "FIVE_STARS")
        seed(id = uuid(2), pv = pv, product = product, rating = "FOUR_STARS")
        seed(id = uuid(3), pv = pv, product = product, rating = "FOUR_STARS")
        seed(id = uuid(4), pv = pv, product = product, rating = "ONE_STARS", visible = false)
        assertThat(entity("ProductVariant", pv, "averageRating")["data"]["_entities"][0]["averageRating"].asDouble())
            .isEqualTo(13.0 / 3)
        // reviews beyond the first page count too; the product averages over all its variants
        (5..30).forEach { seed(id = uuid(it), pv = pv, product = product, rating = if (it <= 25) "FIVE_STARS" else "ONE_STARS") }
        seed(id = uuid(31), product = product, rating = "THREE_STARS")
        assertThat(entity("ProductVariant", pv, "averageRating")["data"]["_entities"][0]["averageRating"].asDouble())
            .isEqualTo(123.0 / 29)
        assertThat(entity("Product", product, "averageRating")["data"]["_entities"][0]["averageRating"].asDouble())
            .isEqualTo(126.0 / 30)
    }

    @Test
    fun `entities - replicated ids resolve, unknown ids resolve to null with an error`() {
        val user = registerUser()
        val result = graphql(
            """{ _entities(representations: [{__typename: "User", id: "$user"}, {__typename: "ProductVariant", id: "${UUID.randomUUID()}"}]) { __typename } }"""
        )
        assertThat(result["data"]["_entities"][0]["__typename"].asString()).isEqualTo("User")
        assertThat(result["data"]["_entities"][1].isNull).isTrue()
        assertThat(result["errors"]).hasSize(1)
    }
}
