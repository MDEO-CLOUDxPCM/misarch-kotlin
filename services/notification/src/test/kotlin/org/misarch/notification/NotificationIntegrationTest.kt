package org.misarch.notification

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.misarch.common.testing.MisarchIntegrationTest
import tools.jackson.databind.JsonNode
import java.io.File
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Behaviour of the notification service against a real Postgres (Testcontainers) and a fake Dapr sidecar:
 * user replica and notification-create events, mutations, queries, connection, federation, authorization.
 */
class NotificationIntegrationTest : MisarchIntegrationTest() {

    private val employee = authorizedUserHeader(UUID.randomUUID(), "employee")
    private val admin = authorizedUserHeader(UUID.randomUUID(), "admin")

    private fun registerUser(id: UUID = UUID.randomUUID()): UUID {
        // the full user/user/created payload of the user service; only the id is used
        val status = deliverEvent(
            "/subscription/user/user/created", "user/user/created",
            """{"id":"$id","username":"u","firstName":"F","lastName":"L","dateJoined":"2024-05-01T10:20:30.123456Z"}"""
        )
        assertThat(status).isEqualTo(200)
        return id
    }

    private fun createNotification(userId: UUID, title: String = "Hello"): String {
        val result = graphql(
            """mutation { createNotification(input: {title: "$title", body: "Body", userId: "$userId"}) { id } }""",
            user = employee
        )
        assertThat(result["errors"]).isNull()
        return result["data"]["createNotification"]["id"].asString()
    }

    private fun userNotifications(userId: UUID, args: String, user: String?): JsonNode = graphql(
        """query(${'$'}r: [_Any!]!) { _entities(representations: ${'$'}r) { ... on User { id notifications$args { totalCount hasNextPage nodes { id title } } } } }""",
        mapOf("r" to listOf(mapOf("__typename" to "User", "id" to userId.toString()))),
        user
    )

    @Test
    fun `dapr subscribe lists the reference subscriptions`() {
        val body = webTestClient.get().uri("/dapr/subscribe").exchange()
            .expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!
        val reference = File(System.getProperty("misarch.reference.dir"), "dapr-subscribe/notification.json").readText()
        assertThat(mapper.readTree(body).toSet()).isEqualTo(mapper.readTree(reference).toSet())
        assertThat(mapper.readTree(body).size()).isEqualTo(2)
    }

    @Test
    fun `health endpoint is UP`() {
        webTestClient.get().uri("/health").exchange()
            .expectStatus().isOk
            .expectBody().json("""{"status":"UP"}""", org.springframework.test.json.JsonCompareMode.STRICT)
    }

    @Test
    fun `user created event registers the replica, a duplicate is acknowledged, invalid payload is rejected`() {
        val id = registerUser()
        val duplicate = deliverEvent("/subscription/user/user/created", "user/user/created", """{"id":"$id"}""")
        assertThat(duplicate).isEqualTo(200)
        val invalid = deliverEvent("/subscription/user/user/created", "user/user/created", """{"username":"x"}""")
        assertThat(invalid).isEqualTo(400)
    }

    @Test
    fun `notification create event creates a notification for a known user only`() {
        val userId = registerUser()
        val status = deliverEvent(
            "/subscription/notification/notification/create", "notification/notification/create",
            """{"title":"From event","body":"Event body","userId":"$userId"}"""
        )
        assertThat(status).isEqualTo(200)
        val list = userNotifications(userId, "", authorizedUserHeader(userId, "buyer"))["data"]["_entities"][0]["notifications"]
        assertThat(list["totalCount"].asInt()).isEqualTo(1)
        assertThat(list["nodes"][0]["title"].asString()).isEqualTo("From event")

        val unknown = deliverEvent(
            "/subscription/notification/notification/create", "notification/notification/create",
            """{"title":"x","body":"y","userId":"${UUID.randomUUID()}"}"""
        )
        assertThat(unknown).isEqualTo(500)
        val invalid = deliverEvent(
            "/subscription/notification/notification/create", "notification/notification/create",
            """{"title":"x","userId":"$userId"}"""
        )
        assertThat(invalid).isEqualTo(400)
        assertThat(dapr.published).isEmpty()
    }

    @Test
    fun `createNotification - employees and admins only, unknown users rejected`() {
        val userId = registerUser()
        val before = OffsetDateTime.now()
        val created = graphql(
            """mutation { createNotification(input: {title: "T", body: "B", userId: "$userId"}) { id title body dateSent dateRead isRead user { id } } }""",
            user = admin
        )
        assertThat(created["errors"]).isNull()
        val n = created["data"]["createNotification"]
        assertThat(n["title"].asString()).isEqualTo("T")
        assertThat(n["body"].asString()).isEqualTo("B")
        assertThat(n["isRead"].asBoolean()).isFalse()
        assertThat(n["dateRead"].isNull).isTrue()
        assertThat(n["user"]["id"].asString()).isEqualTo(userId.toString())
        assertThat(Duration.between(before, OffsetDateTime.parse(n["dateSent"].asString())).abs()).isLessThan(Duration.ofMinutes(1))

        val buyer = graphql(
            """mutation { createNotification(input: {title: "T", body: "B", userId: "$userId"}) { id } }""",
            user = authorizedUserHeader(userId, "buyer")
        )
        assertThat(buyer["errors"][0]["message"].asString())
            .isEqualTo("Exception while fetching data (/createNotification) : Unauthorized access: $userId is not an employee or admin")
        val anonymous = graphql("""mutation { createNotification(input: {title: "T", body: "B", userId: "$userId"}) { id } }""")
        assertThat(anonymous["errors"][0]["message"].asString())
            .isEqualTo("Exception while fetching data (/createNotification) : Unauthorized access")
        val unknown = UUID.randomUUID()
        val unknownUser = graphql(
            """mutation { createNotification(input: {title: "T", body: "B", userId: "$unknown"}) { id } }""",
            user = employee
        )
        assertThat(unknownUser["errors"][0]["message"].asString())
            .isEqualTo("Exception while fetching data (/createNotification) : User with id $unknown does not exist")
        assertThat(dapr.published).isEmpty()
    }

    @Test
    fun `updateNotification - owner toggles read state, employee is rejected, admin allowed`() {
        val userId = registerUser()
        val id = createNotification(userId)
        val owner = authorizedUserHeader(userId, "buyer")
        val read = graphql("""mutation { updateNotification(input: {id: "$id", isRead: true}) { isRead dateRead } }""", user = owner)
        assertThat(read["errors"]).isNull()
        assertThat(read["data"]["updateNotification"]["isRead"].asBoolean()).isTrue()
        val dateRead = read["data"]["updateNotification"]["dateRead"].asString()
        // already read: dateRead unchanged
        val again = graphql("""mutation { updateNotification(input: {id: "$id", isRead: true}) { dateRead } }""", user = owner)
        assertThat(again["data"]["updateNotification"]["dateRead"].asString()).isEqualTo(dateRead)
        val unread = graphql("""mutation { updateNotification(input: {id: "$id", isRead: false}) { isRead dateRead } }""", user = owner)
        assertThat(unread["data"]["updateNotification"]["isRead"].asBoolean()).isFalse()
        assertThat(unread["data"]["updateNotification"]["dateRead"].isNull).isTrue()

        val employeeId = UUID.randomUUID()
        val byEmployee = graphql(
            """mutation { updateNotification(input: {id: "$id", isRead: true}) { id } }""",
            user = authorizedUserHeader(employeeId, "employee")
        )
        assertThat(byEmployee["errors"][0]["message"].asString())
            .isEqualTo("Exception while fetching data (/updateNotification) : Unauthorized access: $employeeId is not an admin")
        val byAdmin = graphql("""mutation { updateNotification(input: {id: "$id", isRead: true}) { isRead } }""", user = admin)
        assertThat(byAdmin["data"]["updateNotification"]["isRead"].asBoolean()).isTrue()
        val anonymous = graphql("""mutation { updateNotification(input: {id: "$id", isRead: true}) { isRead } }""")
        assertThat(anonymous["errors"][0]["message"].asString())
            .isEqualTo("Exception while fetching data (/updateNotification) : Unauthorized access")
        val unknown = graphql("""mutation { updateNotification(input: {id: "${UUID.randomUUID()}", isRead: true}) { isRead } }""", user = admin)
        assertThat(unknown["errors"][0]["message"].asString())
            .isEqualTo("Exception while fetching data (/updateNotification) : null")
    }

    @Test
    fun `notification by id - owner and employee, foreign buyer rejected, unknown id is an error`() {
        val userId = registerUser()
        val id = createNotification(userId)
        val own = graphql("""{ notification(id: "$id") { id user { id } } }""", user = authorizedUserHeader(userId, "buyer"))
        assertThat(own["data"]["notification"]["user"]["id"].asString()).isEqualTo(userId.toString())
        assertThat(graphql("""{ notification(id: "$id") { id } }""", user = employee)["data"]["notification"]["id"].asString()).isEqualTo(id)
        val otherId = UUID.randomUUID()
        val foreign = graphql("""{ notification(id: "$id") { id } }""", user = authorizedUserHeader(otherId, "buyer"))
        assertThat(foreign.path("data").let { it.isMissingNode || it.isNull }).isTrue()
        assertThat(foreign["errors"][0]["message"].asString())
            .isEqualTo("Exception while fetching data (/notification) : Unauthorized access: $otherId is not an employee or admin")
        val anonymous = graphql("""{ notification(id: "$id") { id } }""")
        assertThat(anonymous["errors"][0]["message"].asString())
            .isEqualTo("Exception while fetching data (/notification) : Unauthorized access")
        val unknown = graphql("""{ notification(id: "${UUID.randomUUID()}") { id } }""", user = employee)
        assertThat(unknown.path("data").let { it.isMissingNode || it.isNull }).isTrue()
        assertThat(unknown["errors"]).hasSize(1)
    }

    @Test
    fun `user notifications - authorization, paging, ordering`() {
        val userId = registerUser()
        val ids = (1..3).map { createNotification(userId, "N$it") }.sorted()
        val owner = authorizedUserHeader(userId, "buyer")
        val all = userNotifications(userId, "", owner)["data"]["_entities"][0]["notifications"]
        assertThat(all["totalCount"].asInt()).isEqualTo(3)
        assertThat(all["hasNextPage"].asBoolean()).isFalse()
        assertThat(all["nodes"].toList().map { it["id"].asString() }).containsExactlyElementsOf(ids)
        val page = userNotifications(userId, "(first: 1, skip: 1, orderBy: {field: ID, direction: DESC})", owner)["data"]["_entities"][0]["notifications"]
        assertThat(page["nodes"].toList().map { it["id"].asString() }).containsExactly(ids[1])
        assertThat(page["totalCount"].asInt()).isEqualTo(3)
        assertThat(page["hasNextPage"].asBoolean()).isTrue()
        val lastPage = userNotifications(userId, "(first: 2, skip: 1)", owner)["data"]["_entities"][0]["notifications"]
        assertThat(lastPage["hasNextPage"].asBoolean()).isFalse()
        assertThat(lastPage["nodes"].toList().map { it["id"].asString() }).containsExactly(ids[1], ids[2])

        assertThat(userNotifications(userId, "", employee)["data"]["_entities"][0]["notifications"]["totalCount"].asInt()).isEqualTo(3)
        val otherId = UUID.randomUUID()
        val foreign = userNotifications(userId, "", authorizedUserHeader(otherId, "buyer"))
        assertThat(foreign["errors"][0]["message"].asString())
            .isEqualTo("Exception while fetching data (/_entities[0]/notifications) : Unauthorized access: $otherId is not an employee or admin")
        val anonymous = userNotifications(userId, "", null)
        assertThat(anonymous["errors"][0]["message"].asString()).endsWith("/notifications) : Unauthorized access")
    }

    @Test
    fun `federation - notification entities without authorization, user stubs without existence check`() {
        val userId = registerUser()
        val id = createNotification(userId, "Federated")
        val result = graphql(
            """query(${'$'}r: [_Any!]!) { _entities(representations: ${'$'}r) { ... on Notification { id title isRead user { id } } ... on User { id } } }""",
            mapOf("r" to listOf(
                mapOf("__typename" to "Notification", "id" to id),
                mapOf("__typename" to "User", "id" to UUID.randomUUID().toString())
            ))
        )
        assertThat(result["errors"]).isNull()
        assertThat(result["data"]["_entities"][0]["title"].asString()).isEqualTo("Federated")
        assertThat(result["data"]["_entities"][0]["user"]["id"].asString()).isEqualTo(userId.toString())
        assertThat(result["data"]["_entities"][1]["id"].isString).isTrue()
    }

    @Test
    fun `federation - an unknown notification id fails only its own entry`() {
        val userId = registerUser()
        val id = createNotification(userId, "Known")
        val result = graphql(
            """query(${'$'}r: [_Any!]!) { _entities(representations: ${'$'}r) { ... on Notification { id title } } }""",
            mapOf("r" to listOf(
                mapOf("__typename" to "Notification", "id" to id),
                mapOf("__typename" to "Notification", "id" to UUID.randomUUID().toString())
            ))
        )
        assertThat(result["data"]["_entities"][0]["title"].asString()).isEqualTo("Known")
        assertThat(result["data"]["_entities"][1].isNull).isTrue()
        assertThat(result["errors"]).hasSize(1)
    }
}
