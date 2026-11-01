package org.misarch.user

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.misarch.common.testing.MisarchIntegrationTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitSingle
import kotlinx.coroutines.runBlocking
import tools.jackson.databind.JsonNode
import java.io.File
import java.time.Duration
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Behaviour of the user service against a real Postgres (Testcontainers) and a fake Dapr sidecar:
 * Keycloak's `user/user/create` event, `user/user/created` publication, queries, connection, field-level
 * authorization, updateUser (OptionalInput semantics), federation.
 */
class UserIntegrationTest : MisarchIntegrationTest() {

    @Autowired
    lateinit var databaseClient: DatabaseClient

    private fun createUser(id: UUID = UUID.randomUUID(), username: String = "user-$id"): UUID {
        val status = deliverEvent(
            "/subscription/user/user/create", "user/user/create",
            """{"id":"$id","username":"$username","firstName":"First $username","lastName":"Last $username"}"""
        )
        assertThat(status).isEqualTo(200)
        return id
    }

    private fun entities(ids: List<UUID>, selection: String, user: String?): JsonNode = graphql(
        """query(${'$'}r: [_Any!]!) { _entities(representations: ${'$'}r) { ... on User { $selection } } }""",
        mapOf("r" to ids.map { mapOf("__typename" to "User", "id" to it.toString()) }),
        user
    )

    private val employee = authorizedUserHeader(UUID.randomUUID(), "employee")

    @Test
    fun `dapr subscribe lists the reference subscriptions`() {
        val body = webTestClient.get().uri("/dapr/subscribe").exchange()
            .expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!
        val reference = File(System.getProperty("misarch.reference.dir"), "dapr-subscribe/user.json").readText()
        assertThat(mapper.readTree(body)).isEqualTo(mapper.readTree(reference))
    }

    @Test
    fun `health endpoint is UP`() {
        webTestClient.get().uri("/health").exchange()
            .expectStatus().isOk
            .expectBody().json("""{"status":"UP"}""", org.springframework.test.json.JsonCompareMode.STRICT)
    }

    @Test
    fun `user create event stores the user and publishes user created with the contract payload`() {
        val before = OffsetDateTime.now()
        val id = createUser(username = "keycloak-user")
        val events = dapr.awaitPublished("user/user/created")
        assertThat(events).hasSize(1)
        assertThat(events[0].pubsubName).isEqualTo("pubsub")
        assertThat(events[0].contentType).startsWith("application/json")
        val payload = events[0].json
        assertThat(payload.propertyNames().asSequence().toList())
            .containsExactly("id", "username", "firstName", "lastName", "dateJoined")
        assertThat(payload["id"].asString()).isEqualTo(id.toString())
        assertThat(payload["username"].asString()).isEqualTo("keycloak-user")
        assertThat(payload["firstName"].asString()).isEqualTo("First keycloak-user")
        assertThat(payload["lastName"].asString()).isEqualTo("Last keycloak-user")

        // dateJoined: ISO_OFFSET_DATE_TIME of the value read back from the database (microseconds)
        val stored = runBlocking {
            databaseClient.sql("SELECT dateJoined FROM UserEntity WHERE id = :id").bind("id", id)
                .map { row -> row.get(0, OffsetDateTime::class.java)!! }.awaitSingle()
        }
        val dateJoined = payload["dateJoined"].asString()
        assertThat(dateJoined).isEqualTo(stored.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
        assertThat(dateJoined).matches("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(\.\d{1,6})?(Z|[+-]\d\d:\d\d)""")
        val parsed = OffsetDateTime.parse(dateJoined)
        assertThat(Duration.between(before, parsed).abs()).isLessThan(Duration.ofMinutes(1))

        // new users have no gender/birthday
        val user = graphql("""{ user(id: "$id") { id username name { firstName lastName } birthday gender dateJoined } }""",
            user = authorizedUserHeader(id, "buyer"))
        assertThat(user["errors"]).isNull()
        assertThat(user["data"]["user"]["birthday"].isNull).isTrue()
        assertThat(user["data"]["user"]["gender"].isNull).isTrue()
        assertThat(OffsetDateTime.parse(user["data"]["user"]["dateJoined"].asString()).toInstant())
            .isEqualTo(parsed.toInstant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS))
    }

    @Test
    fun `duplicate user create event is acknowledged without publishing again, invalid payload is rejected`() {
        val id = createUser()
        dapr.awaitPublished("user/user/created")
        dapr.reset()
        val status = deliverEvent(
            "/subscription/user/user/create", "user/user/create",
            """{"id":"$id","username":"again","firstName":"a","lastName":"b"}"""
        )
        assertThat(status).isEqualTo(200)
        assertThat(graphql("""{ user(id: "$id") { username } }""", user = employee)["data"]["user"]["username"].asString())
            .isNotEqualTo("again")
        val invalid = deliverEvent(
            "/subscription/user/user/create", "user/user/create", """{"id":"${UUID.randomUUID()}","firstName":"a","lastName":"b"}"""
        )
        assertThat(invalid).isEqualTo(400)
        assertThat(dapr.published).isEmpty()
    }

    @Test
    fun `failing publish fails the event and rolls back, the redelivery creates and publishes`() {
        val id = UUID.randomUUID()
        dapr.publishStatus = 500
        val status = deliverEvent(
            "/subscription/user/user/create", "user/user/create",
            """{"id":"$id","username":"u","firstName":"a","lastName":"b"}"""
        )
        assertThat(status).isEqualTo(500)
        dapr.publishStatus = 204
        dapr.reset()
        // insert and publish are one transaction: no row is left behind
        val missing = graphql("""{ user(id: "$id") { id } }""", user = employee)
        assertThat(missing["errors"]).hasSize(1)
        val redelivery = deliverEvent(
            "/subscription/user/user/create", "user/user/create",
            """{"id":"$id","username":"u","firstName":"a","lastName":"b"}"""
        )
        assertThat(redelivery).isEqualTo(200)
        assertThat(dapr.awaitPublished("user/user/created").single().body).contains("\"id\":\"$id\"")
        val user = graphql("""{ user(id: "$id") { id } }""", user = employee)
        assertThat(user["data"]["user"]["id"].asString()).isEqualTo(id.toString())
    }

    @Test
    fun `currentUser is null without header, the user with header and an error for an unknown user`() {
        val id = createUser()
        assertThat(graphql("{ currentUser { id } }")["data"]["currentUser"].isNull).isTrue()
        val own = graphql("{ currentUser { id username } }", user = authorizedUserHeader(id, "buyer"))
        assertThat(own["data"]["currentUser"]["id"].asString()).isEqualTo(id.toString())
        val unknown = graphql("{ currentUser { id } }", user = authorizedUserHeader(UUID.randomUUID(), "buyer"))
        assertThat(unknown["data"]["currentUser"].isNull).isTrue()
        assertThat(errorMessage(unknown))
            .isEqualTo("Exception while fetching data (/currentUser) : null")
    }

    @Test
    fun `user by id - authorization and unknown id`() {
        val id = createUser()
        val other = createUser()
        val buyer = authorizedUserHeader(id, "buyer")
        assertThat(graphql("""{ user(id: "$id") { id } }""", user = buyer)["data"]["user"]["id"].asString())
            .isEqualTo(id.toString())
        val foreign = graphql("""{ user(id: "$other") { id } }""", user = buyer)
        assertThat(foreign.path("data").let { it.isMissingNode || it.isNull }).isTrue()
        assertThat(errorMessage(foreign))
            .isEqualTo("Exception while fetching data (/user) : Unauthorized access: $id is not an employee or admin")
        assertThat(graphql("""{ user(id: "$other") { id } }""", user = employee)["data"]["user"]["id"].asString())
            .isEqualTo(other.toString())
        assertThat(graphql("""{ user(id: "$other") { id } }""", user = authorizedUserHeader(UUID.randomUUID(), "admin"))["data"]["user"]["id"].asString())
            .isEqualTo(other.toString())
        val anonymous = graphql("""{ user(id: "$other") { id } }""")
        assertThat(errorMessage(anonymous))
            .isEqualTo("Exception while fetching data (/user) : Unauthorized access")
        val unknown = graphql("""{ user(id: "${UUID.randomUUID()}") { id } }""", user = employee)
        assertThat(unknown.path("data").let { it.isMissingNode || it.isNull }).isTrue()
        assertThat(unknown["errors"]).hasSize(1)
    }

    @Test
    fun `field level authorization of birthday, gender and dateJoined`() {
        val id = createUser()
        val other = createUser()
        val buyer = authorizedUserHeader(id, "buyer")
        // id, username, name are public (also via federation)
        val public = entities(listOf(other), "id username name { firstName }", buyer)
        assertThat(public["errors"]).isNull()
        assertThat(public["data"]["_entities"][0]["username"].asString()).isEqualTo("user-$other")
        // protected fields of another user: error, and the whole (non-null dateJoined) User becomes null
        val protectedFields = entities(listOf(other), "id dateJoined", buyer)
        assertThat(protectedFields["data"]["_entities"][0].isNull).isTrue()
        assertThat(errorMessage(protectedFields))
            .isEqualTo("Exception while fetching data (/_entities[0]/dateJoined) : Unauthorized access: $id is not an employee or admin")
        val nullable = entities(listOf(other), "id gender", buyer)
        assertThat(nullable["data"]["_entities"][0]["id"].asString()).isEqualTo(other.toString())
        assertThat(nullable["data"]["_entities"][0]["gender"].isNull).isTrue()
        assertThat(nullable["errors"]).hasSize(1)
        // without header even the own fields fail
        val anonymous = entities(listOf(other), "id birthday", null)
        assertThat(errorMessage(anonymous)).endsWith(": Unauthorized access")
        // employee and the user themselves see everything
        assertThat(entities(listOf(other), "id birthday gender dateJoined", employee)["errors"]).isNull()
        assertThat(entities(listOf(id), "id birthday gender dateJoined", buyer)["errors"]).isNull()
    }

    @Test
    fun `federation resolves users in batches, an unknown id fails only its own entry`() {
        val a = createUser()
        val b = createUser()
        val both = entities(listOf(a, b), "id", employee)
        assertThat(both["data"]["_entities"].toList().map { it["id"].asString() }).containsExactly(a.toString(), b.toString())
        val withUnknown = entities(listOf(a, UUID.randomUUID()), "id", employee)
        assertThat(withUnknown["data"]["_entities"][0]["id"].asString()).isEqualTo(a.toString())
        assertThat(withUnknown["data"]["_entities"][1].isNull).isTrue()
        assertThat(withUnknown["errors"].size()).isEqualTo(1)
    }

    @Test
    fun `updateUser keeps omitted fields, clears explicit nulls and publishes nothing`() {
        val id = createUser()
        dapr.reset()
        val buyer = authorizedUserHeader(id, "buyer")
        val set = graphql(
            """mutation { updateUser(input: {id: "$id", firstName: "Grace", birthday: "1906-12-09", gender: FEMALE}) { id name { firstName lastName } birthday gender } }""",
            user = buyer
        )
        assertThat(set["errors"]).isNull()
        val user = set["data"]["updateUser"]
        assertThat(user["name"]["firstName"].asString()).isEqualTo("Grace")
        assertThat(user["name"]["lastName"].asString()).isEqualTo("Last user-$id")
        assertThat(user["birthday"].asString()).isEqualTo("1906-12-09")
        assertThat(user["gender"].asString()).isEqualTo("FEMALE")

        // read back from the database (LocalDate in a TIMESTAMPTZ column)
        val read = graphql("""{ user(id: "$id") { birthday gender name { firstName } } }""", user = buyer)["data"]["user"]
        assertThat(read["birthday"].asString()).isEqualTo("1906-12-09")
        assertThat(read["gender"].asString()).isEqualTo("FEMALE")

        // omitted birthday stays, explicit null gender is cleared, null lastName means "unchanged"
        val clear = graphql(
            """mutation { updateUser(input: {id: "$id", lastName: null, gender: null}) { name { firstName lastName } birthday gender } }""",
            user = buyer
        )["data"]["updateUser"]
        assertThat(clear["birthday"].asString()).isEqualTo("1906-12-09")
        assertThat(clear["gender"].isNull).isTrue()
        assertThat(clear["name"]["lastName"].asString()).isEqualTo("Last user-$id")
        val clearBirthday = graphql(
            """mutation(${'$'}b: Date) { updateUser(input: {id: "$id", birthday: ${'$'}b}) { birthday } }""",
            mapOf("b" to null), buyer
        )["data"]["updateUser"]
        assertThat(clearBirthday["birthday"].isNull).isTrue()
        assertThat(dapr.published).isEmpty()
    }

    @Test
    fun `updateUser authorization and unknown user`() {
        val id = createUser()
        val other = createUser()
        val buyer = authorizedUserHeader(id, "buyer")
        val foreign = graphql("""mutation { updateUser(input: {id: "$other", firstName: "X"}) { id } }""", user = buyer)
        assertThat(errorMessage(foreign))
            .isEqualTo("Exception while fetching data (/updateUser) : Unauthorized access: $id is not an employee or admin")
        val byEmployee = graphql("""mutation { updateUser(input: {id: "$other", firstName: "X"}) { name { firstName } } }""", user = employee)
        assertThat(byEmployee["data"]["updateUser"]["name"]["firstName"].asString()).isEqualTo("X")
        val anonymous = graphql("""mutation { updateUser(input: {id: "$other", firstName: "X"}) { id } }""")
        assertThat(errorMessage(anonymous)).isEqualTo("Exception while fetching data (/updateUser) : Unauthorized access")
        val unknown = graphql("""mutation { updateUser(input: {id: "${UUID.randomUUID()}", firstName: "X"}) { id } }""", user = employee)
        assertThat(errorMessage(unknown))
            .isEqualTo("Exception while fetching data (/updateUser) : null")
    }

    @Test
    fun `users connection - employee only, ordering, paging, totalCount, hasNextPage`() {
        val prefix = "zz-${UUID.randomUUID()}"
        val created = listOf("c", "a", "b").map { createUser(username = "$prefix-$it") }
        val buyerResult = graphql("{ users { totalCount } }", user = authorizedUserHeader(created[0], "buyer"))
        assertThat(errorMessage(buyerResult))
            .isEqualTo("Exception while fetching data (/users) : Unauthorized access: ${created[0]} is not an employee or admin")

        fun ids(orderBy: String, first: Int? = null, skip: Int? = null): JsonNode {
            val args = listOfNotNull(
                "orderBy: $orderBy", first?.let { "first: $it" }, skip?.let { "skip: $it" }
            ).joinToString(", ")
            return graphql("{ users($args) { totalCount hasNextPage nodes { id username } } }", user = employee)["data"]["users"]
        }

        val all = ids("{field: ID}")
        val allIds = all["nodes"].toList().map { it["id"].asString() }
        assertThat(all["totalCount"].asInt()).isEqualTo(allIds.size)
        assertThat(all["hasNextPage"].asBoolean()).isFalse()
        assertThat(allIds).containsAll(created.map { it.toString() })
        assertThat(allIds).isSorted()

        val desc = ids("{field: ID, direction: DESC}")["nodes"].toList().map { it["id"].asString() }
        assertThat(desc).isEqualTo(allIds.reversed())

        val byName = ids("{field: USERNAME}")["nodes"].toList().map { it["username"].asString() }.filter { it.startsWith(prefix) }
        assertThat(byName).containsExactly("$prefix-a", "$prefix-b", "$prefix-c")
        val byNameDesc = ids("{field: USERNAME, direction: DESC}")["nodes"].toList().map { it["username"].asString() }.filter { it.startsWith(prefix) }
        assertThat(byNameDesc).containsExactly("$prefix-c", "$prefix-b", "$prefix-a")

        val size = allIds.size
        val page = ids("{direction: ASC}", first = 2, skip = 1)
        assertThat(page["nodes"].toList().map { it["id"].asString() }).isEqualTo(allIds.subList(1, 3))
        assertThat(page["totalCount"].asInt()).isEqualTo(size)
        assertThat(page["hasNextPage"].asBoolean()).isEqualTo(size > 3)
        val last = ids("{field: ID}", first = 2, skip = size - 2)
        assertThat(last["hasNextPage"].asBoolean()).isFalse()
        assertThat(last["nodes"].toList().map { it["id"].asString() }).isEqualTo(allIds.subList(size - 2, size))
        val beforeLast = ids("{field: ID}", first = 1, skip = size - 2)
        assertThat(beforeLast["hasNextPage"].asBoolean()).isTrue()
        val skipOnly = ids("{field: ID}", skip = size - 1)
        assertThat(skipOnly["nodes"].toList().map { it["id"].asString() }).containsExactly(allIds.last())
        assertThat(skipOnly["hasNextPage"].asBoolean()).isFalse()
    }
}
