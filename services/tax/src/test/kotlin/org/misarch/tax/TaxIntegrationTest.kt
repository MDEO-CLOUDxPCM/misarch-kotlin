package org.misarch.tax

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.misarch.common.testing.MisarchIntegrationTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.json.JsonCompareMode
import tools.jackson.databind.JsonNode
import java.util.UUID

/**
 * Behaviour of the tax service against a real Postgres (Testcontainers) and a fake Dapr sidecar. Expected values
 * (error texts, event JSON, formats) were observed on the original tax service (docs/specs/tax.md §9a).
 */
class TaxIntegrationTest : MisarchIntegrationTest() {

    @Autowired
    lateinit var databaseClient: DatabaseClient

    private val employee = authorizedUserHeader(UUID.randomUUID(), "employee")
    private val admin = authorizedUserHeader(UUID.randomUUID(), "admin")
    private val buyerId = UUID.randomUUID()
    private val buyer = authorizedUserHeader(buyerId, "buyer")

    private fun cleanDatabase() {
        databaseClient.sql("UPDATE TaxRateEntity SET currentVersionId = NULL").then().block()
        databaseClient.sql("DELETE FROM TaxRateVersionEntity").then().block()
        databaseClient.sql("DELETE FROM TaxRateEntity").then().block()
    }

    private fun createTaxRate(name: String, rate: Double, user: String = employee, description: String = "d"): JsonNode {
        val result = graphql(
            """mutation(${'$'}i: CreateTaxRateInput!) { createTaxRate(input: ${'$'}i) { id name description currentVersion { id version rate createdAt } } }""",
            mapOf("i" to mapOf("name" to name, "description" to description, "initialVersion" to mapOf("rate" to rate))),
            user
        )
        assertThat(result["errors"]).isNull()
        return result["data"]["createTaxRate"]
    }

    private fun createVersion(taxRateId: String, rate: Double): JsonNode {
        val result = graphql(
            """mutation { createTaxRateVersion(input: {taxRateId: "$taxRateId", rate: $rate}) { id version rate createdAt taxRate { id currentVersion { id version } } } }""",
            user = employee
        )
        assertThat(result["errors"]).isNull()
        return result["data"]["createTaxRateVersion"]
    }

    private fun errorMessage(result: JsonNode): String = result["errors"][0]["message"].asString()

    @Test
    fun `dapr subscribe is empty, health is UP, no experiment-config endpoints`() {
        webTestClient.get().uri("/dapr/subscribe").exchange()
            .expectStatus().isOk
            .expectBody().json("[]", JsonCompareMode.STRICT)
        webTestClient.get().uri("/health").exchange()
            .expectStatus().isOk
            .expectBody().json("""{"status":"UP"}""", JsonCompareMode.STRICT)
        webTestClient.get().uri("/ecs/defined-variables").exchange().expectStatus().isNotFound
    }

    @Test
    fun `createTaxRate requires an employee`() {
        val mutation = """mutation { createTaxRate(input: {name: "n", description: "d", initialVersion: {rate: 0.19}}) { id } }"""
        assertThat(errorMessage(graphql(mutation)))
            .isEqualTo("Exception while fetching data (/createTaxRate) : Unauthorized access")
        assertThat(errorMessage(graphql(mutation, user = buyer)))
            .isEqualTo("Exception while fetching data (/createTaxRate) : Unauthorized access: $buyerId is not an employee or admin")
        // like the original, a failing non-null root field gives no "data" key at all
        assertThat(graphql(mutation).has("data")).isFalse()
        assertThat(dapr.published).isEmpty()
        // admins are employees
        assertThat(createTaxRate("by admin", 7.0, admin)["name"].asString()).isEqualTo("by admin")
    }

    @Test
    fun `createTaxRate stores rate and initial version and publishes both events in order`() {
        val created = createTaxRate("vat", 0.19, description = "standard")
        val id = created["id"].asString()
        val versionId = created["currentVersion"]["id"].asString()
        assertThat(created["currentVersion"]["version"].asInt()).isEqualTo(1)
        assertThat(created["currentVersion"]["rate"].asDouble()).isEqualTo(0.19)
        // DateTime scalar: milliseconds and offset, e.g. 2026-10-13T12:06:08.498Z
        assertThat(created["currentVersion"]["createdAt"].asString()).matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}(Z|[+-]\\d\\d:\\d\\d)")

        val rows = databaseClient.sql("SELECT currentVersionId FROM TaxRateEntity WHERE id = :id")
            .bind("id", UUID.fromString(id)).map { row -> row.get(0).toString() }.one().block()
        assertThat(rows).isEqualTo(versionId)

        val events = dapr.published
        assertThat(events.map { it.topic }).containsExactly("tax/tax-rate/created", "tax/tax-rate-version/created")
        assertThat(events.map { it.pubsubName }).containsOnly("pubsub")
        assertThat(events[0].body)
            .isEqualTo("""{"id":"$id","name":"vat","description":"standard","currentVersionId":"$versionId"}""")
        val versionEvent = events[1].json
        val createdAt = versionEvent["createdAt"].asString()
        assertThat(createdAt).matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d(\\.\\d+)?(Z|[+-]\\d\\d:\\d\\d)")
        assertThat(events[1].body)
            .isEqualTo("""{"id":"$versionId","rate":0.19,"version":1,"createdAt":"$createdAt","taxRateId":"$id"}""")

        createTaxRate("whole", 7.0)
        assertThat(dapr.published("tax/tax-rate-version/created").last().body).contains("\"rate\":7.0,")
    }

    @Test
    fun `createTaxRateVersion increments the version, becomes current and publishes one event`() {
        val taxRate = createTaxRate("versioned", 0.19)
        val id = taxRate["id"].asString()
        dapr.reset()
        val v2 = createVersion(id, -0.5)
        assertThat(v2["version"].asInt()).isEqualTo(2)
        assertThat(v2["rate"].asDouble()).isEqualTo(-0.5)
        assertThat(v2["taxRate"]["currentVersion"]["id"].asString()).isEqualTo(v2["id"].asString())
        val v3 = createVersion(id, 0.07)
        assertThat(v3["version"].asInt()).isEqualTo(3)
        val events = dapr.published
        assertThat(events.map { it.topic }).containsExactly("tax/tax-rate-version/created", "tax/tax-rate-version/created")
        assertThat(events[0].json["version"].asInt()).isEqualTo(2)
        assertThat(events[0].json["taxRateId"].asString()).isEqualTo(id)
        assertThat(events[0].json.propertyNames().asSequence().toList())
            .containsExactly("id", "rate", "version", "createdAt", "taxRateId")

        val unknown = UUID.randomUUID()
        val result = graphql(
            """mutation { createTaxRateVersion(input: {taxRateId: "$unknown", rate: 1}) { id } }""", user = employee
        )
        assertThat(errorMessage(result))
            .isEqualTo("Exception while fetching data (/createTaxRateVersion) : TaxRate with id $unknown does not exist.")
        val forbidden = graphql("""mutation { createTaxRateVersion(input: {taxRateId: "$id", rate: 1}) { id } }""", user = buyer)
        assertThat(errorMessage(forbidden)).endsWith("is not an employee or admin")
    }

    @Test
    fun `updateTaxRate changes only the given fields and publishes nothing`() {
        val id = createTaxRate("old", 0.1, description = "old description")["id"].asString()
        dapr.reset()
        val renamed = graphql("""mutation { updateTaxRate(input: {id: "$id", name: "new"}) { name description } }""", user = employee)
        assertThat(renamed["data"]["updateTaxRate"]["name"].asString()).isEqualTo("new")
        assertThat(renamed["data"]["updateTaxRate"]["description"].asString()).isEqualTo("old description")
        val described = graphql("""mutation { updateTaxRate(input: {id: "$id", description: "x"}) { name description } }""", user = employee)
        assertThat(described["data"]["updateTaxRate"]["name"].asString()).isEqualTo("new")
        assertThat(described["data"]["updateTaxRate"]["description"].asString()).isEqualTo("x")
        assertThat(dapr.published).isEmpty()

        val unknown = graphql("""mutation { updateTaxRate(input: {id: "${UUID.randomUUID()}"}) { name } }""", user = employee)
        assertThat(errorMessage(unknown)).isEqualTo("Exception while fetching data (/updateTaxRate) : null")
        val anonymous = graphql("""mutation { updateTaxRate(input: {id: "$id"}) { name } }""")
        assertThat(errorMessage(anonymous)).isEqualTo("Exception while fetching data (/updateTaxRate) : Unauthorized access")
    }

    @Test
    fun `taxRate and taxRateVersion queries need an employee, unknown ids fail`() {
        val created = createTaxRate("query", 0.2)
        val id = created["id"].asString()
        val versionId = created["currentVersion"]["id"].asString()

        assertThat(errorMessage(graphql("""{ taxRate(id: "$id") { id } }""")))
            .isEqualTo("Exception while fetching data (/taxRate) : Unauthorized access")
        assertThat(errorMessage(graphql("""{ taxRate(id: "$id") { id } }""", user = buyer)))
            .isEqualTo("Exception while fetching data (/taxRate) : Unauthorized access: $buyerId is not an employee or admin")
        assertThat(errorMessage(graphql("""{ taxRateVersion(id: "$versionId") { id } }""")))
            .isEqualTo("Exception while fetching data (/taxRateVersion) : Unauthorized access")
        assertThat(errorMessage(graphql("""{ taxRates { totalCount } }""", user = buyer)))
            .isEqualTo("Exception while fetching data (/taxRates) : Unauthorized access: $buyerId is not an employee or admin")

        val byId = graphql("""{ taxRate(id: "$id") { name currentVersion { version taxRate { id } } } }""", user = employee)
        assertThat(byId["data"]["taxRate"]["name"].asString()).isEqualTo("query")
        assertThat(byId["data"]["taxRate"]["currentVersion"]["taxRate"]["id"].asString()).isEqualTo(id)
        val version = graphql("""{ taxRateVersion(id: "$versionId") { version rate taxRate { name } } }""", user = employee)
        assertThat(version["data"]["taxRateVersion"]["taxRate"]["name"].asString()).isEqualTo("query")

        val unknown = graphql("""{ taxRate(id: "${UUID.randomUUID()}") { id } }""", user = employee)
        assertThat(errorMessage(unknown)).isEqualTo("Exception while fetching data (/taxRate) : null")
        assertThat(unknown.has("data")).isFalse()

        // no Apollo federated tracing: project decision (docs/PORTING.md §7), unlike the original
        assertThat(byId.path("extensions").has("ftv1")).isFalse()
    }

    @Test
    fun `taxRates connection orders, pages and validates paging like QueryDSL`() {
        cleanDatabase()
        val b = createTaxRate("b", 0.1)["id"].asString()
        val a = createTaxRate("a", 0.2)["id"].asString()
        val c = createTaxRate("c", 0.3)["id"].asString()

        fun names(args: String): List<String> =
            graphql("{ taxRates$args { nodes { name } } }", user = employee)["data"]["taxRates"]["nodes"].toList().map { it["name"].asString() }

        assertThat(names("(orderBy: {field: NAME})")).containsExactly("a", "b", "c")
        assertThat(names("(orderBy: {field: NAME, direction: DESC})")).containsExactly("c", "b", "a")
        val byId = listOf("a" to a, "b" to b, "c" to c).sortedBy { it.second }.map { it.first }
        assertThat(names("")).containsExactlyElementsOf(byId)
        assertThat(names("(orderBy: {direction: DESC})")).containsExactlyElementsOf(byId.reversed())

        val page = graphql(
            "{ taxRates(first: 1, skip: 1, orderBy: {field: NAME}) { totalCount hasNextPage nodes { name } } }", user = employee
        )["data"]["taxRates"]
        assertThat(page["totalCount"].asInt()).isEqualTo(3)
        assertThat(page["hasNextPage"].asBoolean()).isTrue()
        assertThat(page["nodes"].toList().map { it["name"].asString() }).containsExactly("b")
        val last = graphql("{ taxRates(first: 2, skip: 1) { hasNextPage nodes { name } } }", user = employee)["data"]["taxRates"]
        assertThat(last["hasNextPage"].asBoolean()).isFalse()
        assertThat(last["nodes"].size()).isEqualTo(2)
        assertThat(graphql("{ taxRates(skip: 2) { hasNextPage nodes { name } } }", user = employee)["data"]["taxRates"]["hasNextPage"].asBoolean())
            .isFalse()

        // QueryDSL paging validation of the original
        val zero = graphql("{ taxRates(first: 0) { totalCount hasNextPage nodes { name } } }", user = employee)
        assertThat(errorMessage(zero)).isEqualTo("Exception while fetching data (/taxRates/nodes) : Limit must be greater than 0.")
        val negativeSkip = graphql("{ taxRates(skip: -1) { nodes { name } } }", user = employee)
        assertThat(errorMessage(negativeSkip)).isEqualTo("Exception while fetching data (/taxRates/nodes) : Offset must not be negative.")
        val negativeFirst = graphql("{ taxRates(first: -1) { hasNextPage } }", user = employee)
        assertThat(errorMessage(negativeFirst)).isEqualTo("Exception while fetching data (/taxRates/hasNextPage) : Offset must not be negative.")
        val probe = graphql("{ taxRates(first: 1, skip: -1) { hasNextPage } }", user = employee)
        assertThat(probe["data"]["taxRates"]["hasNextPage"].asBoolean()).isTrue()
    }

    @Test
    fun `versions connection and federation entities`() {
        val created = createTaxRate("entity", 0.1)
        val id = created["id"].asString()
        createVersion(id, 0.2)
        createVersion(id, 0.3)
        val representations = mapOf("r" to listOf(mapOf("__typename" to "TaxRate", "id" to id)))

        val versions = graphql(
            """query(${'$'}r: [_Any!]!) { _entities(representations: ${'$'}r) { ... on TaxRate {
                 byVersion: versions(orderBy: {field: VERSION, direction: DESC}) { totalCount hasNextPage nodes { version } }
                 byCreatedAt: versions(first: 2, orderBy: {field: CREATED_AT}) { hasNextPage nodes { version } }
               } } }""",
            representations, employee
        )["data"]["_entities"][0]
        assertThat(versions["byVersion"]["totalCount"].asInt()).isEqualTo(3)
        assertThat(versions["byVersion"]["hasNextPage"].asBoolean()).isFalse()
        assertThat(versions["byVersion"]["nodes"].toList().map { it["version"].asInt() }).containsExactly(3, 2, 1)
        assertThat(versions["byCreatedAt"]["hasNextPage"].asBoolean()).isTrue()
        assertThat(versions["byCreatedAt"]["nodes"].toList().map { it["version"].asInt() }).containsExactly(1, 2)

        // entities, currentVersion and taxRate need no authorization; versions does
        val anonymous = graphql(
            """query(${'$'}r: [_Any!]!) { _entities(representations: ${'$'}r) {
                 ... on TaxRate { name currentVersion { version taxRate { name } } }
                 ... on TaxRateVersion { version createdAt taxRate { name } } } }""",
            mapOf("r" to listOf(
                mapOf("__typename" to "TaxRate", "id" to id),
                mapOf("__typename" to "TaxRateVersion", "id" to created["currentVersion"]["id"].asString())
            ))
        )
        assertThat(anonymous["errors"]).isNull()
        assertThat(anonymous["data"]["_entities"][0]["currentVersion"]["version"].asInt()).isEqualTo(3)
        assertThat(anonymous["data"]["_entities"][1]["version"].asInt()).isEqualTo(1)
        assertThat(anonymous["data"]["_entities"][1]["taxRate"]["name"].asString()).isEqualTo("entity")

        val forbidden = graphql(
            """query(${'$'}r: [_Any!]!) { _entities(representations: ${'$'}r) { ... on TaxRate { versions { totalCount } } } }""",
            representations
        )
        assertThat(errorMessage(forbidden)).isEqualTo("Exception while fetching data (/_entities[0]/versions) : Unauthorized access")
        assertThat(forbidden["data"]["_entities"][0].isNull).isTrue()

        val unknown = graphql(
            """query(${'$'}r: [_Any!]!) { _entities(representations: ${'$'}r) { ... on TaxRate { name } } }""",
            mapOf("r" to listOf(mapOf("__typename" to "TaxRate", "id" to UUID.randomUUID().toString())))
        )
        assertThat(unknown["errors"]).isNotNull()
    }

    @Test
    fun `invalid Authorized-User header is a 400 without body`() {
        webTestClient.post().uri("/graphql")
            .header("Content-Type", "application/json")
            .header("Authorized-User", """{"id":"x"}""")
            .bodyValue(mapOf("query" to "{ taxRates { totalCount } }"))
            .exchange()
            .expectStatus().isBadRequest
    }

    @Test
    fun `a failing publish does not roll back (no transaction, like the original)`() {
        dapr.publishStatus = 500
        val result = graphql(
            """mutation { createTaxRate(input: {name: "unpublished", description: "d", initialVersion: {rate: 0.5}}) { id } }""",
            user = employee
        )
        dapr.publishStatus = 204
        assertThat(result["errors"]).isNotNull()
        val count = databaseClient.sql("SELECT COUNT(*) FROM TaxRateEntity WHERE name = 'unpublished' AND currentVersionId IS NOT NULL")
            .map { row -> (row.get(0) as Number).toInt() }.one().block()
        assertThat(count).isEqualTo(1)
    }
}
