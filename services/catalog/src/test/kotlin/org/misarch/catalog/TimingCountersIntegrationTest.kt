package org.misarch.catalog

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.misarch.common.testing.MisarchIntegrationTest
import org.misarch.common.timing.counters.CpuCounters
import org.springframework.test.context.TestPropertySource
import tools.jackson.databind.JsonNode
import java.util.UUID

/**
 * The CPU counters (`misarch.timing.counters=true`) in a whole service: operations of every kind (GraphQL fields,
 * `_entities`, data loader, Dapr event by topic, HTTP route), statements, and `GET /timing/counters`.
 */
@TestPropertySource(properties = ["misarch.timing.counters=true"])
class TimingCountersIntegrationTest : MisarchIntegrationTest() {

    private val employee = authorizedUserHeader(UUID.randomUUID(), "employee")

    private fun JsonNode.ok(): JsonNode {
        assertThat(this["errors"]).describedAs("errors: %s", this["errors"]).isNull()
        return this["data"]
    }

    private fun counters(): JsonNode = mapper.readTree(
        webTestClient.get().uri("/timing/counters").exchange().expectStatus().isOk.expectBody(String::class.java)
            .returnResult().responseBody!!
    )

    private fun op(c: JsonNode, key: String): JsonNode? = c["operations"].firstOrNull { it["key"].asString() == key }

    @Test
    fun `operations of every kind, statements, and the endpoint`() {
        // the subscription list teaches the filter route -> topic (the sidecar fetches it at start)
        webTestClient.get().uri("/dapr/subscribe").exchange().expectStatus().isOk
        val taxRate = UUID.randomUUID()
        assertThat(deliverEvent("/subscription/tax/tax-rate/created", "tax/tax-rate/created", """{"id":"$taxRate"}"""))
            .isEqualTo(200)
        val category = graphql(
            """mutation { createCategory(input: { name: "c-${UUID.randomUUID()}", description: "d",
              categoricalCharacteristics: [{ name: "Color", description: "c" }], numericalCharacteristics: [] })
              { id characteristics { nodes { id } } } }""", user = employee
        ).ok()["createCategory"]
        val characteristic = category["characteristics"]["nodes"][0]["id"].asString()
        val product = graphql(
            """mutation { createProduct(input: { internalName: "p-${UUID.randomUUID()}", isPubliclyVisible: true,
              categoryIds: ["${category["id"].asString()}"],
              defaultVariant: { isPubliclyVisible: true, initialVersion: { name: "V1", description: "D", retailPrice: 20,
                canBeReturnedForDays: 30, weight: 0.5, taxRateId: "$taxRate", mediaIds: [],
                categoricalCharacteristicValues: [{characteristicId: "$characteristic", value: "red"}],
                numericalCharacteristicValues: [] } } })
              { id defaultVariant { id } } }""", user = employee
        ).ok()["createProduct"]
        val variant = product["defaultVariant"]["id"].asString()
        val before = counters()
        val framesBefore = CpuCounters.framesEntered.sum()
        val n = 50
        repeat(n) {
            graphql("""{ product(id: "${product["id"].asString()}") { id variants { totalCount nodes { id } } categories { nodes { id } } } }""")
                .ok()
            graphql(
                """query(${'$'}r: [_Any!]!) { _entities(representations: ${'$'}r) { ... on ProductVariant { id product { id } } } }""",
                mapOf("r" to listOf(mapOf("__typename" to "ProductVariant", "id" to variant))),
            ).ok()
        }
        val framesPerRequest = (CpuCounters.framesEntered.sum() - framesBefore).toDouble() / (2 * n)
        val c = counters()

        assertThat(c.propertyNames().asSequence().toList())
            .containsExactly("enabled", "t", "statements", "operations", "threadCpuNsTotal")
        assertThat(c["enabled"].asBoolean()).isTrue()
        val kinds = c["operations"].associate { it["key"].asString() to it["kind"].asString() }
        assertThat(kinds).containsEntry("tax/tax-rate/created", "event")
            .containsEntry("Mutation.createProduct", "graphql")
            .containsEntry("Query.product", "graphql")
            .containsEntry("_entities.ProductVariant", "entities")
            .containsEntry("ProductDataLoader", "dataloader")
            .containsEntry("POST /graphql", "http")
            .containsEntry("GET /dapr/subscribe", "http")
        assertThat(op(c, "Query.product")!!["calls"].asLong() - (op(before, "Query.product")?.get("calls")?.asLong() ?: 0))
            .isEqualTo(n.toLong())
        assertThat(op(c, "_entities.ProductVariant")!!["cpuNs"].asLong()).isGreaterThan(0)
        assertThat(op(c, "POST /graphql")!!["cpuNs"].asLong()).isGreaterThan(0)
        for (o in c["operations"]) {
            assertThat(o.propertyNames().asSequence().toList()).containsExactly("key", "kind", "calls", "cpuNs")
        }
        val statements = c["statements"].toList()
        for (s in statements) {
            assertThat(s.propertyNames().asSequence().toList())
                .containsExactly("key", "sql", "entity", "mode", "calls", "rows", "driverCpuNs", "mappingCpuNs")
        }
        val variants = statements.filter {
            it["sql"].asString().startsWith("SELECT COUNT(t.id) FROM \"productvariantentity\" t WHERE")
        }
        assertThat(variants).allMatch { it["mode"].asString() == "read" }
        assertThat(variants.sumOf { it["calls"].asLong() }).isGreaterThanOrEqualTo(n.toLong())
        val insert = statements.first { it["sql"].asString().startsWith("INSERT INTO \"productentity\"") }
        assertThat(insert["mode"].asString()).isEqualTo("write")
        assertThat(insert["entity"].asString()).isEqualTo("ProductEntity")
        val reads = statements.filter { it["mode"].asString() == "read" && it["rows"].asLong() > 0 }
        assertThat(reads.sumOf { it["mappingCpuNs"].asLong() }).isGreaterThan(0)
        assertThat(statements.sumOf { it["driverCpuNs"].asLong() }).isGreaterThan(0)
        // attributed CPU is a part of all threads' CPU
        val attributed = c["operations"].sumOf { it["cpuNs"].asLong() } +
            statements.sumOf { it["driverCpuNs"].asLong() + it["mappingCpuNs"].asLong() }
        assertThat(attributed).isLessThan(c["threadCpuNsTotal"].asLong())
        // overhead estimate: frames per request x the cost of a frame (CpuCountersTest: ~0.4 us)
        val attributedPerRequest = (attributed - (before["operations"].sumOf { it["cpuNs"].asLong() } +
            before["statements"].sumOf { it["driverCpuNs"].asLong() + it["mappingCpuNs"].asLong() })).toDouble() / (2 * n)
        println("timing counters: %.1f frames per request, attributed CPU %.0f us per request"
            .format(framesPerRequest, attributedPerRequest / 1000))
    }
}
