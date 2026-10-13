package org.misarch.address

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.misarch.common.testing.MisarchIntegrationTest
import java.util.UUID

/**
 * Behaviour of the address service against a real Postgres (Testcontainers) and a fake Dapr sidecar:
 * event subscription, mutations + published events, queries, federation entities, authorization.
 */
class AddressIntegrationTest : MisarchIntegrationTest() {

    private fun registerUser(id: UUID) {
        val status = deliverEvent("/subscription/user/user/created", "user/user/created", """{"id":"$id"}""")
        assertThat(status).isEqualTo(200)
    }


    @Test
    fun `dapr subscribe lists the user created topic`() {
        val body = webTestClient.get().uri("/dapr/subscribe").exchange()
            .expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!
        val subscriptions = mapper.readTree(body)
        assertThat(subscriptions.size()).isEqualTo(1)
        assertThat(subscriptions[0]["pubsubName"].asString()).isEqualTo("pubsub")
        assertThat(subscriptions[0]["topic"].asString()).isEqualTo("user/user/created")
        assertThat(subscriptions[0]["route"].asString()).isEqualTo("/subscription/user/user/created")
    }

    @Test
    fun `health endpoint is UP`() {
        webTestClient.get().uri("/health").exchange()
            .expectStatus().isOk
            .expectBody().json("""{"status":"UP"}""", org.springframework.test.json.JsonCompareMode.STRICT)
    }

    @Test
    fun `user creates own address, event is published, address is listed and can be archived`() {
        val userId = UUID.randomUUID()
        registerUser(userId)
        val buyer = authorizedUserHeader(userId, "buyer")

        val created = graphql(
            """
            mutation(${'$'}input: CreateUserAddressInput!) {
              createUserAddress(input: ${'$'}input) { id name { firstName lastName } street1 street2 city postalCode country companyName isArchived archivedAt user { id } }
            }
            """.trimIndent(),
            mapOf(
                "input" to mapOf(
                    "name" to mapOf("firstName" to "Ada", "lastName" to "Lovelace"),
                    "street1" to "Main St 1", "street2" to "", "city" to "Stuttgart", "postalCode" to "70569",
                    "country" to "DE", "companyName" to null, "userId" to userId.toString()
                )
            ),
            buyer
        )
        assertThat(created["errors"]).isNull()
        val address = created["data"]["createUserAddress"]
        val addressId = address["id"].asString()
        assertThat(address["name"]["firstName"].asString()).isEqualTo("Ada")
        assertThat(address["isArchived"].asBoolean()).isFalse()
        assertThat(address["archivedAt"].isNull).isTrue()
        assertThat(address["user"]["id"].asString()).isEqualTo(userId.toString())

        // published event: same JSON as the original (Dapr SDK serializer: nulls omitted)
        val events = dapr.awaitPublished("address/user-address/created")
        assertThat(events).hasSize(1)
        assertThat(events[0].pubsubName).isEqualTo("pubsub")
        assertThat(events[0].contentType).startsWith("application/json")
        val payload = events[0].json
        assertThat(payload.propertyNames().asSequence().toSet()).containsExactlyInAnyOrder(
            "id", "name", "street1", "street2", "city", "postalCode", "country", "userId"
        )
        assertThat(payload["id"].asString()).isEqualTo(addressId)
        assertThat(payload["userId"].asString()).isEqualTo(userId.toString())
        assertThat(payload["name"]["lastName"].asString()).isEqualTo("Lovelace")

        // query by id and via the federated User entity
        val byId = graphql("""{ address(id: "$addressId") { id city ... on UserAddress { isArchived } } }""", user = buyer)
        assertThat(byId["data"]["address"]["city"].asString()).isEqualTo("Stuttgart")

        val entities = graphql(
            """
            query(${'$'}r: [_Any!]!) { _entities(representations: ${'$'}r) {
              ... on User { id addresses(first: 10, filter: {isArchived: false}) { totalCount hasNextPage nodes { id } } }
            } }
            """.trimIndent(),
            mapOf("r" to listOf(mapOf("__typename" to "User", "id" to userId.toString()))),
            buyer
        )
        val connection = entities["data"]["_entities"][0]["addresses"]
        assertThat(connection["totalCount"].asInt()).isEqualTo(1)
        assertThat(connection["hasNextPage"].asBoolean()).isFalse()
        assertThat(connection["nodes"][0]["id"].asString()).isEqualTo(addressId)

        // archive
        val archived = graphql(
            """mutation { archiveUserAddress(input: {id: "$addressId"}) { id isArchived archivedAt } }""",
            user = buyer
        )
        assertThat(archived["errors"]).isNull()
        assertThat(archived["data"]["archiveUserAddress"]["isArchived"].asBoolean()).isTrue()
        val archiveEvents = dapr.awaitPublished("address/user-address/archived")
        assertThat(archiveEvents).hasSize(1)
        assertThat(archiveEvents[0].json["id"].asString()).isEqualTo(addressId)
        assertThat(archiveEvents[0].json["userId"].asString()).isEqualTo(userId.toString())

        val afterArchive = graphql(
            """
            query(${'$'}r: [_Any!]!) { _entities(representations: ${'$'}r) {
              ... on User { active: addresses(filter: {isArchived: false}) { totalCount } archived: addresses(filter: {isArchived: true}) { totalCount nodes { id } } }
            } }
            """.trimIndent(),
            mapOf("r" to listOf(mapOf("__typename" to "User", "id" to userId.toString()))),
            buyer
        )
        assertThat(afterArchive["data"]["_entities"][0]["active"]["totalCount"].asInt()).isEqualTo(0)
        assertThat(afterArchive["data"]["_entities"][0]["archived"]["totalCount"].asInt()).isEqualTo(1)
    }

    @Test
    fun `pagination and ordering of a user's addresses`() {
        val userId = UUID.randomUUID()
        registerUser(userId)
        val buyer = authorizedUserHeader(userId, "buyer")
        val ids = (1..3).map { i ->
            graphql(
                """mutation { createUserAddress(input: {street1: "S $i", street2: "", city: "C", postalCode: "P", country: "DE", userId: "$userId"}) { id name { firstName } } }""",
                user = buyer
            ).also { assertThat(it["data"]["createUserAddress"]["name"].isNull).isTrue() }["data"]["createUserAddress"]["id"].asString()
        }
        val event = dapr.awaitPublished("address/user-address/created", 3)[0].json
        assertThat(event.has("name")).isFalse()
        assertThat(event.has("companyName")).isFalse()

        val page = graphql(
            """
            query(${'$'}r: [_Any!]!) { _entities(representations: ${'$'}r) {
              ... on User { addresses(first: 2, skip: 1, orderBy: {field: ID, direction: DESC}) { totalCount hasNextPage nodes { id } } }
            } }
            """.trimIndent(),
            mapOf("r" to listOf(mapOf("__typename" to "User", "id" to userId.toString()))),
            buyer
        )["data"]["_entities"][0]["addresses"]
        val sortedDesc = ids.sortedDescending()
        assertThat(page["totalCount"].asInt()).isEqualTo(3)
        assertThat(page["hasNextPage"].asBoolean()).isFalse()
        assertThat(page["nodes"].toList().map { it["id"].asString() }).containsExactly(sortedDesc[1], sortedDesc[2])

        val first = graphql(
            """
            query(${'$'}r: [_Any!]!) { _entities(representations: ${'$'}r) {
              ... on User { addresses(first: 1) { hasNextPage nodes { id } } }
            } }
            """.trimIndent(),
            mapOf("r" to listOf(mapOf("__typename" to "User", "id" to userId.toString()))),
            buyer
        )["data"]["_entities"][0]["addresses"]
        assertThat(first["hasNextPage"].asBoolean()).isTrue()
        assertThat(first["nodes"][0]["id"].asString()).isEqualTo(ids.sorted()[0])
    }

    @Test
    fun `invalid paging arguments fail like the QueryDSL original`() {
        val userId = UUID.randomUUID()
        registerUser(userId)
        val buyer = authorizedUserHeader(userId, "buyer")
        fun page(args: String) = graphql(
            """query(${'$'}r: [_Any!]!) { _entities(representations: ${'$'}r) { ... on User { addresses($args) { nodes { id } } } } }""",
            mapOf("r" to listOf(mapOf("__typename" to "User", "id" to userId.toString()))),
            buyer
        )["errors"][0]["message"].asString()
        assertThat(page("first: 0")).isEqualTo("Exception while fetching data (/_entities[0]/addresses/nodes) : Limit must be greater than 0.")
        assertThat(page("skip: -1")).isEqualTo("Exception while fetching data (/_entities[0]/addresses/nodes) : Offset must not be negative.")
    }

    @Test
    fun `buyers cannot create addresses for other users or vendor addresses`() {
        val userId = UUID.randomUUID()
        val otherId = UUID.randomUUID()
        registerUser(userId)
        registerUser(otherId)
        val buyer = authorizedUserHeader(userId, "buyer")
        val foreign = graphql(
            """mutation { createUserAddress(input: {street1: "S", street2: "", city: "C", postalCode: "P", country: "DE", userId: "$otherId"}) { id } }""",
            user = buyer
        )
        assertThat(foreign["errors"][0]["message"].asString()).contains("Unauthorized access: $userId is not an employee or admin")
        val vendor = graphql(
            """mutation { createVendorAddress(input: {street1: "S", street2: "", city: "C", postalCode: "P", country: "DE"}) { id } }""",
            user = buyer
        )
        assertThat(vendor["errors"][0]["message"].asString()).contains("is not an employee or admin")
        val anonymous = graphql(
            """mutation { createVendorAddress(input: {street1: "S", street2: "", city: "C", postalCode: "P", country: "DE"}) { id } }"""
        )
        assertThat(anonymous["errors"][0]["message"].asString()).contains("Unauthorized access")
        assertThat(dapr.published).isEmpty()
    }

    @Test
    fun `employee creates vendor addresses, the newest is the current vendor address`() {
        val employee = authorizedUserHeader(UUID.randomUUID(), "employee")
        val ids = (1..2).map { i ->
            graphql(
                """mutation { createVendorAddress(input: {name: {firstName: "V", lastName: "$i"}, street1: "S $i", street2: "x", city: "C", postalCode: "P", country: "DE", companyName: "ACME"}) { id companyName } }""",
                user = employee
            )["data"]["createVendorAddress"]["id"].asString()
        }
        val events = dapr.awaitPublished("address/vendor-address/created", 2)
        assertThat(events).hasSize(2)
        assertThat(events[1].json["companyName"].asString()).isEqualTo("ACME")
        assertThat(events[1].json.has("userId")).isFalse()

        val current = graphql("{ vendorAddress { id street1 } }")
        assertThat(current["data"]["vendorAddress"]["id"].asString()).isEqualTo(ids[1])

        val entity = graphql(
            """query(${'$'}r: [_Any!]!) { _entities(representations: ${'$'}r) { ... on VendorAddress { id city } ... on UserAddress { id } } }""",
            mapOf("r" to listOf(mapOf("__typename" to "VendorAddress", "id" to ids[0]), mapOf("__typename" to "UserAddress", "id" to ids[0])))
        )
        assertThat(entity["data"]["_entities"][0]["id"].asString()).isEqualTo(ids[0])
        assertThat(entity["data"]["_entities"][1].isNull).isTrue()
    }

    @Test
    fun `address of an unknown user cannot be created and a duplicate user event is acknowledged (FIXED)`() {
        val employee = authorizedUserHeader(UUID.randomUUID(), "employee")
        val unknown = UUID.randomUUID()
        val result = graphql(
            """mutation { createUserAddress(input: {street1: "S", street2: "", city: "C", postalCode: "P", country: "DE", userId: "$unknown"}) { id } }""",
            user = employee
        )
        assertThat(result["errors"][0]["message"].asString()).contains("User with id $unknown does not exist")

        val userId = UUID.randomUUID()
        registerUser(userId)
        val status = deliverEvent("/subscription/user/user/created", "user/user/created", """{"id":"$userId"}""")
        assertThat(status).isEqualTo(200)
    }

    @Test
    fun `an unknown address id fails only its own key (FIXED)`() {
        val userId = UUID.randomUUID()
        registerUser(userId)
        val buyer = authorizedUserHeader(userId, "buyer")
        val created = graphql(
            """mutation { createUserAddress(input: {street1: "S", street2: "", city: "C", postalCode: "P", country: "DE", userId: "$userId"}) { id } }""",
            user = buyer
        )
        val id = created["data"]["createUserAddress"]["id"].asString()
        val result = graphql(
            """query(${'$'}r: [_Any!]!) { _entities(representations: ${'$'}r) { ... on UserAddress { id city } } }""",
            mapOf("r" to listOf(
                mapOf("__typename" to "UserAddress", "id" to id),
                mapOf("__typename" to "UserAddress", "id" to UUID.randomUUID().toString())
            ))
        )
        assertThat(result["data"]["_entities"][0]["city"].asString()).isEqualTo("C")
        assertThat(result["data"]["_entities"][1].isNull).isTrue()
        assertThat(result["errors"]).hasSize(1)
        val unknown = graphql("""{ address(id: "${UUID.randomUUID()}") { id } }""", user = buyer)
        assertThat(unknown["errors"][0]["message"].asString()).isEqualTo("Exception while fetching data (/address) : null")
    }

    @Test
    fun `failing publish fails the mutation and rolls back`() {
        val userId = UUID.randomUUID()
        registerUser(userId)
        val buyer = authorizedUserHeader(userId, "buyer")
        dapr.publishStatus = 500
        val result = graphql(
            """mutation { createUserAddress(input: {street1: "S", street2: "", city: "C", postalCode: "P", country: "DE", userId: "$userId"}) { id } }""",
            user = buyer
        )
        assertThat(result["errors"]).isNotNull()
        dapr.publishStatus = 204
        val count = graphql(
            """query(${'$'}r: [_Any!]!) { _entities(representations: ${'$'}r) { ... on User { addresses { totalCount } } } }""",
            mapOf("r" to listOf(mapOf("__typename" to "User", "id" to userId.toString()))),
            buyer
        )["data"]["_entities"][0]["addresses"]["totalCount"].asInt()
        assertThat(count).isEqualTo(0)
    }
}
