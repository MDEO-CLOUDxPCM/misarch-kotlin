package org.misarch.review

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.misarch.common.testing.MisarchIntegrationTest
import org.misarch.common.testing.SqlStatementLog
import org.springframework.context.annotation.Import
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitOneOrNull
import org.springframework.r2dbc.core.awaitRowsUpdated
import tools.jackson.databind.JsonNode
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Equivalence tests of docs/specs/review.md §10 (Postgres via Testcontainers, full HTTP stack). */
@Import(SqlStatementLog::class)
class ReviewIntegrationTest : MisarchIntegrationTest() {

    override val graphqlPath: String = "/"

    @Autowired
    lateinit var db: DatabaseClient

    @BeforeEach
    fun cleanReviews() = runBlocking {
        db.sql("DELETE FROM reviews").fetch().awaitRowsUpdated()
        Unit
    }

    private val reviewFields = "id user { id } productVariant { id } body rating createdAt lastUpdatedAt isVisible"

    private fun admin() = authorizedUserHeader(UUID.randomUUID(), "admin")
    private fun buyer(id: UUID) = authorizedUserHeader(id, "buyer")

    private fun newUser(): UUID = UUID.randomUUID().also {
        assertEquals(200, deliverEvent("/on-topic-event", "user/user/created", """{"id":"$it","username":"u"}"""))
    }

    private fun newProductVariant(productId: UUID = UUID.randomUUID()): UUID = UUID.randomUUID().also {
        assertEquals(
            200,
            deliverEvent(
                "/on-product-variant-creation-event", "catalog/product-variant/created",
                """{"id":"$it","productId":"$productId","currentVersionId":"${UUID.randomUUID()}","isPubliclyVisible":true}"""
            )
        )
    }

    private fun createReview(user: UUID, pv: UUID, rating: String = "FIVE_STARS", extra: String = "", header: String? = buyer(user)) =
        graphql(
            """mutation { createReview(input: {userId: "$user", productVariantId: "$pv", body: "nice", rating: $rating $extra}) { $reviewFields } }""",
            user = header,
        )

    private fun error(result: JsonNode): String = result.path("errors").path(0).path("message").asString()

    private fun rawPost(path: String, contentType: String?, body: String): Int =
        webTestClient.post().uri(path)
            .apply { if (contentType != null) header("Content-Type", contentType) }
            .bodyValue(body)
            .exchange().returnResult(String::class.java).status.value()

    private fun rawGraphql(query: String): Pair<Int, String?> {
        val result = webTestClient.post().uri("/").header("Content-Type", "application/json")
            .bodyValue(mapOf("query" to query)).exchange().expectBody(String::class.java).returnResult()
        return result.status.value() to result.responseBody
    }

    /** Inserts a review row directly (controlled ids, ratings and timestamps). */
    private fun seed(
        id: UUID = UUID.randomUUID(), user: UUID = UUID.randomUUID(), pv: UUID = UUID.randomUUID(),
        product: UUID = UUID.randomUUID(), rating: String = "FiveStars", visible: Boolean = true,
        created: Instant = Instant.parse("2026-01-01T00:00:00Z"), updated: Instant = created,
    ): UUID = runBlocking {
        db.sql(
            "INSERT INTO reviews VALUES (:id, :user, :pv, :product, 'body', :rating, :created, :updated, :visible)"
        ).bind("id", id).bind("user", user).bind("pv", pv).bind("product", product).bind("rating", rating)
            .bind("created", created.atOffset(ZoneOffset.UTC)).bind("updated", updated.atOffset(ZoneOffset.UTC))
            .bind("visible", visible).fetch().awaitRowsUpdated()
        id
    }

    private fun storedRating(id: UUID): String? = runBlocking {
        db.sql("SELECT rating FROM reviews WHERE id = :id").bind("id", id)
            .map { row -> row.get("rating", String::class.java)!! }.awaitOneOrNull()
    }

    private fun ids(connection: JsonNode): List<String> = connection.path("nodes").toList().map { it.path("id").asString() }

    // ------------------------------------------------------------------ Dapr

    @Test
    fun `dapr subscribe equals the original`() {
        val body = webTestClient.get().uri("/dapr/subscribe").exchange().expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!
        val reference = File(System.getProperty("misarch.reference.dir"), "dapr-subscribe/review.json").readText()
        assertEquals(mapper.readTree(reference), mapper.readTree(body))
        assertEquals(reference.trim(), body.trim())
    }

    @Test
    fun `health answers 200`() {
        webTestClient.get().uri("/health").exchange().expectStatus().isOk.expectBody().isEmpty()
    }

    @Test
    fun `GET root serves GraphiQL`() {
        val html = webTestClient.get().uri("/").exchange().expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!
        assertTrue(html.contains("GraphiQL"))
    }

    @Test
    fun `user and product creation events are stored, redeliveries are idempotent (FIXED)`() {
        val user = UUID.randomUUID()
        val body = webTestClient.post().uri("/on-topic-event").header("Content-Type", "application/cloudevents+json")
            .bodyValue("""{"topic":"user/user/created","data":{"id":"$user","firstName":"a"}}""")
            .exchange().expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody
        assertEquals("""{"status":0}""", body)
        assertEquals(200, deliverEvent("/on-topic-event", "user/user/created", """{"id":"$user"}"""))
        val product = UUID.randomUUID()
        assertEquals(200, deliverEvent("/on-topic-event", "catalog/product/created", """{"id":"$product","internalName":"p"}"""))
        assertEquals(200, deliverEvent("/on-topic-event", "catalog/product/created", """{"id":"$product"}"""))
        val entities = graphql(
            """{ _entities(representations: [{__typename: "User", id: "$user"}, {__typename: "Product", id: "$product"}]) { ... on User { id } ... on Product { id } } }"""
        )
        assertEquals(user.toString(), entities.path("data").path("_entities").path(0).path("id").asString())
        assertEquals(product.toString(), entities.path("data").path("_entities").path(1).path("id").asString())
    }

    @Test
    fun `product variant events on the subscribed route are stored and enable createReview (FIXED D1)`() {
        val pv = UUID.randomUUID()
        val product = UUID.randomUUID()
        // the real catalog payload (publiclyVisible, as catalog publishes it)
        val payload = """{"id":"$pv","productId":"$product","currentVersionId":"${UUID.randomUUID()}","publiclyVisible":true}"""
        assertEquals(200, deliverEvent("/on-topic-event", "catalog/product-variant/created", payload))
        assertEquals(200, deliverEvent("/on-topic-event", "catalog/product-variant/created", payload))
        assertEquals(422, deliverEvent("/on-topic-event", "catalog/product-variant/created", """{"id":"${UUID.randomUUID()}"}"""))
        val entities = graphql("""{ _entities(representations: [{__typename: "ProductVariant", id: "$pv"}]) { ... on ProductVariant { id averageRating } } }""")
        assertEquals(pv.toString(), entities.path("data").path("_entities").path(0).path("id").asString())
        assertTrue(entities.path("data").path("_entities").path(0).path("averageRating").isNull)
        assertTrue(entities.path("errors").isMissingNode)
        val user = newUser()
        val review = createReview(user, pv, "FOUR_STARS").path("data").path("createReview")
        assertEquals(pv.toString(), review.path("productVariant").path("id").asString())
        val rated = graphql("""{ _entities(representations: [{__typename: "ProductVariant", id: "$pv"}]) { ... on ProductVariant { averageRating reviews { totalCount } } } }""")
            .path("data").path("_entities").path(0)
        assertEquals(4.0, rated.path("averageRating").asDouble())
        assertEquals(1, rated.path("reviews").path("totalCount").asInt())
    }

    @Test
    fun `event routes reproduce axum status codes`() {
        assertEquals(500, deliverEvent("/on-topic-event", "other/topic", """{"id":"${UUID.randomUUID()}"}"""))
        assertEquals(422, deliverEvent("/on-topic-event", "user/user/created", """{"name":"x"}"""))
        assertEquals(422, deliverEvent("/on-topic-event", "user/user/created", """{"id":"not-a-uuid"}"""))
        assertEquals(422, rawPost("/on-topic-event", "application/json", """{"data":{"id":"${UUID.randomUUID()}"}}"""))
        assertEquals(400, rawPost("/on-topic-event", "application/json", """{"topic":"""))
        assertEquals(400, rawPost("/on-topic-event", "application/json", ""))
        assertEquals(415, rawPost("/on-topic-event", "text/plain", """{"topic":"user/user/created","data":{"id":"${UUID.randomUUID()}"}}"""))
        assertEquals(415, rawPost("/on-topic-event", null, """{"topic":"user/user/created","data":{"id":"${UUID.randomUUID()}"}}"""))
        // the user id given in simple form is accepted (uuid crate parser)
        val user = UUID.randomUUID()
        assertEquals(200, rawPost("/on-topic-event", "application/json; charset=utf-8", """{"topic":"user/user/created","data":{"id":"${user.toString().replace("-", "")}"}}"""))
    }

    @Test
    fun `direct product variant route`() {
        val pv = newProductVariant()
        assertEquals(200, deliverEvent("/on-product-variant-creation-event", "catalog/product-variant/created", """{"id":"$pv","productId":"${UUID.randomUUID()}"}"""))
        assertEquals(500, deliverEvent("/on-product-variant-creation-event", "user/user/created", """{"id":"${UUID.randomUUID()}","productId":"${UUID.randomUUID()}"}"""))
        assertEquals(422, deliverEvent("/on-product-variant-creation-event", "catalog/product-variant/created", """{"id":"${UUID.randomUUID()}"}"""))
        val entities = graphql("""{ _entities(representations: [{__typename: "ProductVariant", id: "$pv"}]) { ... on ProductVariant { id averageRating } } }""")
        assertEquals(pv.toString(), entities.path("data").path("_entities").path(0).path("id").asString())
        assertTrue(entities.path("data").path("_entities").path(0).path("averageRating").isNull)
    }

    // ------------------------------------------------------------------ createReview

    @Test
    fun `createReview authorizes before validating`() {
        val user = UUID.randomUUID()
        val unknownPv = UUID.randomUUID()
        assertEquals(
            "Authentication failed. Authorized-User header is not set or could not be parsed.",
            error(createReview(user, unknownPv, header = null))
        )
        assertEquals(
            "Authentication failed. Authorized-User header is not set or could not be parsed.",
            error(createReview(user, unknownPv, header = """{"id":"$user","roles":["buyer","offline_access"]}"""))
        )
        val other = UUID.randomUUID()
        val result = createReview(user, unknownPv, header = buyer(other))
        assertEquals("Authentication failed for user of UUID: `$other`. Operation not permitted.", error(result))
        assertTrue(result.path("data").isNull)
    }

    @Test
    fun `createReview fails for unknown variant (the deployment case) and unknown user`() {
        val user = newUser()
        val pv = UUID.randomUUID()
        assertEquals("Product variant with the UUID: `$pv` is not present in the system.", error(createReview(user, pv)))
        val knownPv = newProductVariant()
        val unknownUser = UUID.randomUUID()
        assertEquals(
            "misarch_review::graphql::model::user::User with UUID: `$unknownUser` not found.",
            error(createReview(unknownUser, knownPv))
        )
    }

    @Test
    fun `createReview happy path, duplicate and admin`() {
        val user = newUser()
        val product = UUID.randomUUID()
        val pv = newProductVariant(product)
        val review = createReview(user, pv, "THREE_STARS").path("data").path("createReview")
        assertEquals(user.toString(), review.path("user").path("id").asString())
        assertEquals(pv.toString(), review.path("productVariant").path("id").asString())
        assertEquals("nice", review.path("body").asString())
        assertEquals("THREE_STARS", review.path("rating").asString())
        assertTrue(review.path("isVisible").asBoolean())
        val createdAt = review.path("createdAt").asString()
        assertEquals(createdAt, review.path("lastUpdatedAt").asString())
        assertTrue(Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(\.\d{3})?\+00:00""").matches(createdAt), createdAt)
        assertEquals(
            "User of UUID: `$user` has already written a review for product variant of UUID: `$pv`.",
            error(createReview(user, pv))
        )
        // admin may write for another user; isVisible false; product filter uses the copied productId
        val other = newUser()
        val hidden = createReview(other, pv, "ONE_STARS", "isVisible: false", header = admin()).path("data").path("createReview")
        assertFalse(hidden.path("isVisible").asBoolean())
        val byProduct = graphql("""{ _entities(representations: [{__typename: "Product", id: "$product"}]) { ... on Product { reviews { totalCount } averageRating } } }""")
        // the product itself is not replicated -> null entity
        assertTrue(byProduct.path("data").path("_entities").path(0).isNull)
        assertEquals(200, deliverEvent("/on-topic-event", "catalog/product/created", """{"id":"$product"}"""))
        val product2 = graphql("""{ _entities(representations: [{__typename: "Product", id: "$product"}]) { ... on Product { reviews { totalCount } averageRating } } }""")
            .path("data").path("_entities").path(0)
        assertEquals(2, product2.path("reviews").path("totalCount").asInt())
        assertEquals(3.0, product2.path("averageRating").asDouble())
    }

    // ------------------------------------------------------------------ update / delete

    @Test
    fun `updateReview and deleteReview load before authorizing`() {
        val unknown = UUID.randomUUID()
        val notFound = "misarch_review::graphql::model::review::Review with UUID: `$unknown` not found."
        assertEquals(notFound, error(graphql("""mutation { updateReview(input: {id: "$unknown", body: "x"}) { id } }""")))
        assertEquals(notFound, error(graphql("""mutation { deleteReview(id: "$unknown") }""")))
        val owner = UUID.randomUUID()
        val id = seed(user = owner)
        val stranger = UUID.randomUUID()
        assertEquals(
            "Authentication failed for user of UUID: `$stranger`. Operation not permitted.",
            error(graphql("""mutation { updateReview(input: {id: "$id", body: "x"}) { id } }""", user = buyer(stranger)))
        )
        assertEquals(
            "Authentication failed. Authorized-User header is not set or could not be parsed.",
            error(graphql("""mutation { deleteReview(id: "$id") }"""))
        )
    }

    @Test
    fun `updateReview partial updates and no-op`() {
        val owner = UUID.randomUUID()
        val created = Instant.parse("2026-01-01T10:00:00Z")
        val id = seed(user = owner, rating = "TwoStars", created = created)
        val noop = graphql("""mutation { updateReview(input: {id: "$id"}) { lastUpdatedAt body } }""", user = buyer(owner))
            .path("data").path("updateReview")
        assertEquals("2026-01-01T10:00:00+00:00", noop.path("lastUpdatedAt").asString())
        val updated = graphql(
            """mutation { updateReview(input: {id: "$id", body: "new", rating: FIVE_STARS, isVisible: false}) { body rating isVisible createdAt lastUpdatedAt } }""",
            user = buyer(owner)
        ).path("data").path("updateReview")
        assertEquals("new", updated.path("body").asString())
        assertEquals("FIVE_STARS", updated.path("rating").asString())
        assertFalse(updated.path("isVisible").asBoolean())
        assertEquals("2026-01-01T10:00:00+00:00", updated.path("createdAt").asString())
        assertTrue(updated.path("lastUpdatedAt").asString() != "2026-01-01T10:00:00+00:00")
        assertEquals("FiveStars", storedRating(id))
    }

    @Test
    fun `FOUR_STARS update stores FourStars and the review stays readable (FIXED Q1)`() {
        val owner = UUID.randomUUID()
        val pv = UUID.randomUUID()
        val id = seed(user = owner, pv = pv, rating = "OneStars")
        val result = graphql("""mutation { updateReview(input: {id: "$id", rating: FOUR_STARS}) { id rating } }""", user = buyer(owner))
        assertEquals("FOUR_STARS", result.path("data").path("updateReview").path("rating").asString())
        assertEquals("FourStars", storedRating(id))
        assertEquals("FOUR_STARS", graphql("""{ review(id: "$id") { rating } }""").path("data").path("review").path("rating").asString())
        assertEquals(200, rawGraphql("{ reviews { totalCount } }").first)
        assertEquals(200, deliverEvent("/on-product-variant-creation-event", "catalog/product-variant/created", """{"id":"$pv","productId":"${UUID.randomUUID()}"}"""))
        val avg = graphql("""{ _entities(representations: [{__typename: "ProductVariant", id: "$pv"}]) { ... on ProductVariant { averageRating } } }""")
        assertEquals(4.0, avg.path("data").path("_entities").path(0).path("averageRating").asDouble())
        assertTrue(graphql("""mutation { deleteReview(id: "$id") }""", user = admin()).path("data").path("deleteReview").asBoolean())
    }

    @Test
    fun `one review per user and product variant is enforced by a unique index (FIXED race)`() {
        val user = UUID.randomUUID()
        val pv = UUID.randomUUID()
        seed(user = user, pv = pv)
        val second = runCatching { seed(user = user, pv = pv) }
        assertTrue(second.isFailure)
    }

    @Test
    fun `deleteReview returns true`() {
        val owner = UUID.randomUUID()
        val id = seed(user = owner)
        assertTrue(graphql("""mutation { deleteReview(id: "$id") }""", user = buyer(owner)).path("data").path("deleteReview").asBoolean())
        assertTrue(graphql("""{ review(id: "$id") { id } }""").path("data").path("review").isNull)
    }

    // ------------------------------------------------------------------ queries / connections

    @Test
    fun `review by id, unknown id is null, lenient uuid forms`() {
        val id = seed(rating = "ThreeStars")
        val review = graphql("""{ review(id: "{${id.toString().uppercase()}}") { id rating createdAt } }""").path("data").path("review")
        assertEquals(id.toString(), review.path("id").asString())
        assertEquals("THREE_STARS", review.path("rating").asString())
        assertEquals("2026-01-01T00:00:00+00:00", review.path("createdAt").asString())
        val missing = graphql("""{ review(id: "${UUID.randomUUID()}") { id } }""")
        assertTrue(missing.path("data").path("review").isNull)
        assertTrue(missing.path("errors").isMissingNode)
    }

    private fun uuid(i: Int): UUID = UUID.fromString("%08x-0000-4000-8000-000000000000".format(i))

    @Test
    fun `reviews connection default 25, first, skip (FIXED skip off-by-one)`() {
        val all = (1..30).map { seed(id = uuid(it)) }.map(UUID::toString)
        val default = graphql("{ reviews { totalCount hasNextPage nodes { id } } }").path("data").path("reviews")
        assertEquals(30, default.path("totalCount").asInt())
        assertTrue(default.path("hasNextPage").asBoolean())
        assertEquals(all.take(25), ids(default))
        val first5 = graphql("{ reviews(first: 5) { hasNextPage nodes { id } } }").path("data").path("reviews")
        assertEquals(all.take(5), ids(first5))
        assertTrue(first5.path("hasNextPage").asBoolean())
        val tail = graphql("{ reviews(first: 10, skip: 25) { hasNextPage nodes { id } } }").path("data").path("reviews")
        assertEquals(all.drop(25), ids(tail))
        assertFalse(tail.path("hasNextPage").asBoolean())
        // skip > 0 and exactly first+1 rows remaining: first nodes, hasNextPage true (was first+1 nodes, false)
        val offByOne = graphql("{ reviews(first: 4, skip: 25) { hasNextPage nodes { id } } }").path("data").path("reviews")
        assertEquals(all.drop(25).take(4), ids(offByOne))
        assertTrue(offByOne.path("hasNextPage").asBoolean())
        val lastPage = graphql("{ reviews(first: 5, skip: 25) { hasNextPage nodes { id } } }").path("data").path("reviews")
        assertEquals(all.drop(25), ids(lastPage))
        assertFalse(lastPage.path("hasNextPage").asBoolean())
        val middle = graphql("{ reviews(first: 3, skip: 25) { hasNextPage nodes { id } } }").path("data").path("reviews")
        assertEquals(all.drop(25).take(3), ids(middle))
        assertTrue(middle.path("hasNextPage").asBoolean())
        val zero = graphql("{ reviews(first: 0) { hasNextPage totalCount nodes { id } } }").path("data").path("reviews")
        assertEquals(0, ids(zero).size)
        assertTrue(zero.path("hasNextPage").asBoolean())
        val exact = graphql("{ reviews(first: 30) { hasNextPage nodes { id } } }").path("data").path("reviews")
        assertEquals(30, ids(exact).size)
        assertFalse(exact.path("hasNextPage").asBoolean())
        assertEquals(
            "Failed to parse \"Int\": Invalid number",
            error(graphql("{ reviews(first: -1) { totalCount } }"))
        )
    }

    @Test
    fun `connection fields are separate statements, each run only when selected`() {
        (1..3).forEach { seed(id = uuid(it)) }
        val countOnly = SqlStatementLog.record { graphql("{ reviews { totalCount } }") }
        assertEquals(listOf("SELECT COUNT(t.id) FROM reviews t"), countOnly)
        val nodesOnly = SqlStatementLog.record { graphql("{ reviews(first: 2, skip: 1) { nodes { id } } }") }
        assertEquals(listOf("SELECT t.* FROM reviews t ORDER BY id ASC LIMIT 2 OFFSET 1"), nodesOnly)
        val user = newUser()
        seed(user = user)
        val nested = SqlStatementLog.record {
            val result = graphql("""{ _entities(representations: [{__typename: "User", id: "$user"}]) { ... on User { reviews { hasNextPage } } } }""")
            assertFalse(result.path("data").path("_entities").path(0).path("reviews").path("hasNextPage").asBoolean())
        }
        // the users replica lookup of the entity resolver, then only the probe
        assertEquals(2, nested.size)
        assertEquals("SELECT 1 FROM reviews t WHERE t.user_id = \$1 LIMIT 1 OFFSET 25", nested[1])
    }

    @Test
    fun `an undeserializable row panics only when the nodes are read`() {
        seed(rating = "FourStarst")
        assertEquals(1, graphql("{ reviews { totalCount } }").path("data").path("reviews").path("totalCount").asInt())
        assertEquals(500, rawGraphql("{ reviews { nodes { id } } }").first)
    }

    @Test
    fun `empty connection`() {
        val empty = graphql("{ reviews(skip: 3) { hasNextPage totalCount nodes { id } } }").path("data").path("reviews")
        assertEquals(0, empty.path("totalCount").asInt())
        assertFalse(empty.path("hasNextPage").asBoolean())
    }

    @Test
    fun `order fields (FIXED numeric RATING and CREATED_AT by created_at)`() {
        val a = seed(id = uuid(1), user = uuid(30), pv = uuid(21), product = uuid(40), rating = "TwoStars",
            created = Instant.parse("2026-01-03T00:00:00Z"), updated = Instant.parse("2026-03-01T00:00:00Z"))
        val b = seed(id = uuid(2), user = uuid(10), pv = uuid(22), rating = "FiveStars",
            created = Instant.parse("2026-01-01T00:00:00Z"), updated = Instant.parse("2026-02-01T00:00:00Z"))
        val c = seed(id = uuid(3), user = uuid(10), pv = uuid(21), product = uuid(40), rating = "OneStars",
            created = Instant.parse("2025-01-01T00:00:00Z"), updated = Instant.parse("2026-02-01T00:00:00Z"))
        val highBit = seed(id = UUID.fromString("f0000000-0000-4000-8000-000000000000"), user = uuid(20), pv = uuid(23),
            rating = "FourStars", created = Instant.parse("2026-01-02T00:00:00Z"), updated = Instant.parse("2026-04-01T00:00:00Z"))
        fun order(field: String, dir: String) =
            ids(graphql("{ reviews(orderBy: {field: $field, direction: $dir}) { nodes { id } } }").path("data").path("reviews"))
        fun s(vararg u: UUID) = u.map(UUID::toString)
        assertEquals(s(a, b, c, highBit), order("ID", "ASC"))
        assertEquals(s(highBit, c, b, a), order("ID", "DESC"))
        // ties broken by id DESC regardless of the direction
        assertEquals(s(c, b, highBit, a), order("USER_ID", "ASC"))
        assertEquals(s(a, highBit, c, b), order("USER_ID", "DESC"))
        assertEquals(s(c, a, b, highBit), order("PRODUCT_VARIANT", "ASC"))
        // number of stars: One < Two < Four < Five
        assertEquals(s(c, a, highBit, b), order("RATING", "ASC"))
        assertEquals(s(b, highBit, a, c), order("RATING", "DESC"))
        // CREATED_AT sorts by created_at (last_updated_at would give c, b, a, highBit)
        assertEquals(s(c, b, highBit, a), order("CREATED_AT", "ASC"))
        assertEquals(s(a, highBit, b, c), order("CREATED_AT", "DESC"))
        val defaults = ids(graphql("{ reviews(orderBy: {}) { nodes { id } } }").path("data").path("reviews"))
        assertEquals(s(a, b, c, highBit), defaults)
    }

    @Test
    fun `nested connections filter by user, product variant and product`() {
        val user = newUser()
        val pv = UUID.randomUUID()
        val product = UUID.randomUUID()
        seed(id = uuid(1), user = user, pv = pv, product = product)
        seed(id = uuid(2), user = user)
        seed(id = uuid(3), pv = pv, product = product)
        seed(id = uuid(4), product = product)
        val byUser = graphql("""{ _entities(representations: [{__typename: "User", id: "$user"}]) { ... on User { reviews { totalCount nodes { id user { id } } } } } }""")
            .path("data").path("_entities").path(0).path("reviews")
        assertEquals(listOf(uuid(1), uuid(2)).map(UUID::toString), ids(byUser))
        val nested = graphql("""{ review(id: "${uuid(1)}") { productVariant { reviews { nodes { id } } } user { reviews { totalCount } } } }""")
            .path("data").path("review")
        assertEquals(listOf(uuid(1), uuid(3)).map(UUID::toString), ids(nested.path("productVariant").path("reviews")))
        assertEquals(2, nested.path("user").path("reviews").path("totalCount").asInt())
        assertEquals(200, deliverEvent("/on-topic-event", "catalog/product/created", """{"id":"$product"}"""))
        val byProduct = graphql("""{ _entities(representations: [{__typename: "Product", id: "$product"}]) { ... on Product { reviews { totalCount } } } }""")
        assertEquals(3, byProduct.path("data").path("_entities").path(0).path("reviews").path("totalCount").asInt())
    }

    @Test
    fun `averageRating is f32, ignores invisible reviews and counts all reviews (FIXED Q4)`() {
        val pv = newProductVariant()
        val none = graphql("""{ _entities(representations: [{__typename: "ProductVariant", id: "$pv"}]) { ... on ProductVariant { averageRating } } }""")
        assertTrue(none.path("data").path("_entities").path(0).path("averageRating").isNull)
        seed(id = uuid(1), pv = pv, rating = "FiveStars")
        seed(id = uuid(2), pv = pv, rating = "FourStars")
        seed(id = uuid(3), pv = pv, rating = "FourStars")
        seed(id = uuid(4), pv = pv, rating = "OneStars", visible = false)
        val (status, body) = rawGraphql("""{ _entities(representations: [{__typename: "ProductVariant", id: "$pv"}]) { ... on ProductVariant { averageRating } } }""")
        assertEquals(200, status)
        assertTrue(body!!.contains("\"averageRating\":4.333333492279053"), body)
        // reviews beyond the first 25 (by id) are counted too
        (5..30).forEach { seed(id = uuid(it), pv = pv, rating = if (it <= 25) "FiveStars" else "OneStars") }
        val avg = graphql("""{ _entities(representations: [{__typename: "ProductVariant", id: "$pv"}]) { ... on ProductVariant { averageRating reviews { totalCount } } } }""")
            .path("data").path("_entities").path(0)
        assertEquals(30, avg.path("reviews").path("totalCount").asInt())
        // all visible: 13 (#1-#3) + 21*5 (#5-#25) + 5*1 (#26-#30) = 123 over 29 (was 118 / 24 over the first 25)
        assertEquals((123f / 29f).toDouble(), avg.path("averageRating").asDouble())
    }

    @Test
    fun `_entities fails as a whole like async-graphql`() {
        val unknownType = graphql("""{ _entities(representations: [{__typename: "Review", id: "${UUID.randomUUID()}"}]) { __typename } }""")
        assertEquals("Entity not found.", error(unknownType))
        assertTrue(unknownType.path("data").isNull)
        val badId = graphql("""{ _entities(representations: [{__typename: "User", id: "x"}]) { __typename } }""")
        assertEquals("Entity not found.", error(badId))
        val noTypename = graphql("""{ _entities(representations: [{id: "${UUID.randomUUID()}"}]) { __typename } }""")
        assertEquals("\"__typename\" must be an existing string.", error(noTypename))
        val mixed = graphql("""{ _entities(representations: [{__typename: "User", id: "${UUID.randomUUID()}"}, {__typename: "ProductVariant", id: "${UUID.randomUUID()}"}]) { __typename } }""")
        assertEquals(2, mixed.path("data").path("_entities").size())
        assertTrue(mixed.path("data").path("_entities").toList().all { it.isNull })
    }

    @Test
    fun `batch requests are rejected`() {
        webTestClient.post().uri("/").header("Content-Type", "application/json")
            .bodyValue("""[{"query":"{ reviews { totalCount } }"}]""").exchange().expectStatus().isBadRequest
    }
}
