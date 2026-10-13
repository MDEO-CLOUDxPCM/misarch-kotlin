package org.misarch.common.testing

import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * Base class of service integration tests: full application on a random port, Flyway-migrated Postgres
 * ([PostgresTestDatabase]), and a [FakeDaprSidecar] as Dapr HTTP endpoint (records published events).
 *
 * ```
 * class AddressIntegrationTest : MisarchIntegrationTest() {
 *     @Test fun `creates address`() {
 *         val result = graphql("mutation { ... }", user = buyer(userId))
 *         assertThat(dapr.awaitPublished("address/user-address/created")).hasSize(1)
 *     }
 * }
 * ```
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
abstract class MisarchIntegrationTest {

    @Autowired
    lateinit var webTestClient: WebTestClient

    /** The fake Dapr sidecar (shared by all tests of the JVM; reset before each test). */
    val dapr: FakeDaprSidecar get() = sharedDapr

    @BeforeEach
    fun resetDapr() = sharedDapr.reset()

    /** GraphQL endpoint path of the service (`/graphql`; ports of Rust services serve GraphQL at `/`). */
    open val graphqlPath: String = "/graphql"

    /**
     * Executes a GraphQL request and returns the whole response document (`data`, `errors`).
     *
     * @param user value of the `Authorized-User` header (see [authorizedUserHeader]) or null
     */
    fun graphql(query: String, variables: Map<String, Any?> = emptyMap(), user: String? = null): JsonNode {
        val body = webTestClient.post().uri(graphqlPath)
            .header("Content-Type", "application/json")
            .apply { if (user != null) header("Authorized-User", user) }
            .bodyValue(mapOf("query" to query, "variables" to variables))
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
        return mapper.readTree(body)
    }

    /** POSTs a CloudEvent with [data] (JSON) to the subscription [route], as Dapr does; returns the status code. */
    fun deliverEvent(route: String, topic: String, data: String, pubsubName: String = "pubsub"): Int {
        val cloudEvent = """
            {"id":"${java.util.UUID.randomUUID()}","source":"test","type":"com.dapr.event.sent","specversion":"1.0",
             "datacontenttype":"application/json","topic":"$topic","pubsubname":"$pubsubName","data":$data}
        """.trimIndent()
        return webTestClient.post().uri(route)
            .header("Content-Type", "application/cloudevents+json")
            .bodyValue(cloudEvent)
            .exchange()
            .returnResult(String::class.java)
            .status.value()
    }

    companion object {
        val mapper: JsonMapper = JsonMapper.builder().build()

        private val sharedDapr: FakeDaprSidecar by lazy { FakeDaprSidecar() }

        /** `Authorized-User` header value. */
        fun authorizedUserHeader(id: java.util.UUID, vararg roles: String): String =
            """{"id":"$id","roles":[${roles.joinToString(",") { "\"$it\"" }}]}"""

        @JvmStatic
        @DynamicPropertySource
        fun misarchProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.r2dbc.url") { PostgresTestDatabase.r2dbcUrl }
            registry.add("spring.r2dbc.username") { "postgres" }
            registry.add("spring.r2dbc.password") { "postgres" }
            registry.add("spring.flyway.url") { PostgresTestDatabase.jdbcUrl }
            registry.add("misarch.dapr.http-endpoint") { sharedDapr.endpoint }
        }
    }
}
