package org.misarch.common.testing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.test.web.reactive.server.WebTestClient
import tools.jackson.databind.json.JsonMapper
import java.io.File

/**
 * SDL test every service has (name it `<Service>SdlTest`, run alone with `:services:<name>:sdlTest` or
 * tools/sdl-diff.sh). Starts the application without database (Flyway off, unreachable R2DBC URL - nothing connects
 * at startup), queries `{ _service { sdl } }` and compares it semantically ([SdlCanonicalizer]) with the reference:
 * `reference/sdl/<service>.graphql` (the running original's `_service { sdl }`), or, if missing,
 * `reference/schemas/<service>.graphql` (the misarch/schemas repository).
 *
 * Writes `build/sdl/<service>.graphql` (raw) and `build/sdl/<service>.{actual,reference}.canonical.graphql`.
 *
 * ```
 * class AddressSdlTest : SdlComparisonTest("address")
 * ```
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.flyway.enabled=false",
        "spring.r2dbc.url=r2dbc:postgresql://127.0.0.1:1/none",
        "spring.r2dbc.username=none",
        "spring.r2dbc.password=none",
        "misarch.dapr.http-endpoint=http://127.0.0.1:1",
    ]
)
@AutoConfigureWebTestClient
abstract class SdlComparisonTest(private val serviceName: String) {

    @Autowired
    lateinit var webTestClient: WebTestClient

    /** GraphQL endpoint path of the service (`/graphql`; ports of Rust services serve GraphQL at `/`). */
    open val graphqlPath: String = "/graphql"

    /** Compare descriptions too (default; `-Pmisarch.sdl.descriptions=false` for the sdlTest task switches it off). */
    open val compareDescriptions: Boolean = System.getProperty("misarch.sdl.descriptions") != "false"

    @Test
    fun `federation SDL equals the reference`() {
        val response = webTestClient.post().uri(graphqlPath)
            .header("Content-Type", "application/json")
            .bodyValue(mapOf("query" to "{ _service { sdl } }"))
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
        val sdl = JsonMapper.builder().build().readTree(response).path("data").path("_service").path("sdl").asString()
        val outDir = File("build/sdl").apply { mkdirs() }
        File(outDir, "$serviceName.graphql").writeText(sdl)

        val referenceRoot = File(System.getProperty("misarch.reference.dir") ?: "../../reference")
        val candidates = listOf(File(referenceRoot, "sdl/$serviceName.graphql"), File(referenceRoot, "schemas/$serviceName.graphql"))
        val reference = (if (System.getProperty("misarch.sdl.reference") == "schemas") candidates.reversed() else candidates)
            .firstOrNull { it.exists() } ?: error("No reference SDL for $serviceName in $referenceRoot/{sdl,schemas}")
        val expected = SdlCanonicalizer.canonicalize(reference.readText(), compareDescriptions)
        val actual = SdlCanonicalizer.canonicalize(sdl, compareDescriptions)
        File(outDir, "$serviceName.reference.canonical.graphql").writeText("# from ${reference.path}\n$expected")
        File(outDir, "$serviceName.actual.canonical.graphql").writeText("# from build/sdl/$serviceName.graphql\n$actual")
        assertEquals(expected, actual, "SDL differs from ${reference.path}; run tools/sdl-diff.sh $serviceName")
        assertTrue(sdl.contains("https://specs.apollo.dev/federation/v2.5"), "SDL must link federation v2.5")
    }
}
