package org.misarch.common.mechanisms

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.misarch.common.timing.TestTracing
import org.misarch.common.timing.TimingSpans
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebHandler
import org.springframework.web.server.handler.FilteringWebHandler
import reactor.core.publisher.Mono
import java.util.concurrent.atomic.AtomicInteger

/** The response cache (docs/MECHANISMS.md): switch, hit/miss, TTL, what is cached, and the `misarch.cache` spans. */
class EntityCacheTest {

    private val tracing = TestTracing()

    @AfterEach
    fun tearDown() {
        TimingSpans.disable()
        tracing.close()
    }

    /** A subgraph that answers every request with a fresh body (call number), or with `errors` if asked. */
    private class Subgraph {
        val calls = AtomicInteger()
        val handler = WebHandler { exchange ->
            DataBufferUtils.join(exchange.request.body).flatMap { buffer ->
                val request = buffer.toString(Charsets.UTF_8).also { DataBufferUtils.release(buffer) }
                val n = calls.incrementAndGet()
                val body = if ("fail" in request) """{"data":null,"errors":[{"message":"x"}]}""" else """{"data":{"n":$n}}"""
                exchange.response.headers.contentType = MediaType.APPLICATION_JSON
                exchange.response.writeWith(Mono.just(exchange.response.bufferFactory().wrap(body.toByteArray())))
            }
        }
    }

    private fun client(subgraph: Subgraph, filter: WebFilter?): WebTestClient =
        WebTestClient.bindToWebHandler(FilteringWebHandler(subgraph.handler, listOfNotNull(filter))).configureClient().build()

    private fun WebTestClient.post(body: String, user: String? = null): String =
        post().uri("/graphql").contentType(MediaType.APPLICATION_JSON)
            .apply { if (user != null) header("Authorized-User", user) }
            .bodyValue(body).exchange().expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!

    private fun entities(type: String, id: String, variable: String = "representations", extra: String = "") =
        """{"query":"query(${'$'}$variable:[_Any!]!){_entities(representations:${'$'}$variable){... on $type{id $extra}}}",""" +
            """"variables":{"$variable":[{"__typename":"$type","id":"$id"}]}}"""

    @Test
    fun `off - no filter bean without types, filter with types (property or env name)`() {
        val runner = ApplicationContextRunner().withUserConfiguration(EntityCacheConfiguration::class.java)
        runner.run { assertTrue(it.getBeansOfType(WebFilter::class.java).isEmpty()) }
        runner.withPropertyValues("$TYPES_PROPERTY= ").run { assertTrue(it.getBeansOfType(WebFilter::class.java).isEmpty()) }
        runner.withPropertyValues("$TYPES_PROPERTY=Order").run {
            assertEquals(1, it.getBeansOfType(WebFilter::class.java).size)
        }
        // the short env name (resolved like any property source entry)
        runner.withPropertyValues("MISARCH_ENTITY_CACHE_TYPES=ProductVariant", "MISARCH_ENTITY_CACHE_TTL_SECONDS=5").run {
            assertTrue(it.getBean(WebFilter::class.java) is EntityCacheFilter)
        }
    }

    @Test
    fun `hit, miss and keys`() {
        val subgraph = Subgraph()
        val filter = EntityCacheFilter(setOf("Order"), 60_000, 100)
        val client = client(subgraph, filter)
        val a = entities("Order", "1")
        assertEquals("""{"data":{"n":1}}""", client.post(a))
        assertEquals("""{"data":{"n":1}}""", client.post(a))                     // hit: the subgraph is not called
        assertEquals(1, subgraph.calls.get())
        assertEquals("""{"data":{"n":2}}""", client.post(entities("Order", "2")))  // other representation
        assertEquals("""{"data":{"n":3}}""", client.post(a, user = """{"id":"u"}""")) // other user
        assertEquals("""{"data":{"n":3}}""", client.post(a, user = """{"id":"u"}"""))
        // the gateway's batched variable name
        val v0 = entities("Order", "1", variable = "_v0_representations")
        assertEquals("""{"data":{"n":4}}""", client.post(v0))
        assertEquals("""{"data":{"n":4}}""", client.post(v0))
        assertEquals(3L to 4L, filter.counts)
        // not cached: other types, non-entity queries, responses with errors
        val product = entities("Product", "1")
        client.post(product); client.post(product)
        client.post("""{"query":"{orders{id}}"}"""); client.post("""{"query":"{orders{id}}"}""")
        val failing = entities("Order", "9", extra = "fail")
        client.post(failing); client.post(failing)
        assertEquals(10, subgraph.calls.get())
        assertEquals(3L to 6L, filter.counts)
        // the counters, without tracing
        val counts = client.get().uri(COUNTS_PATH).exchange().expectStatus().isOk.expectBody(String::class.java)
            .returnResult().responseBody!!
        assertEquals("""{"types":["Order"],"ttlMs":60000,"hits":3,"misses":6,"entries":4}""", counts)
        assertEquals(10, subgraph.calls.get())
    }

    @Test
    fun `ttl 0 - every lookup misses, expired entries miss`() {
        val subgraph = Subgraph()
        val zero = EntityCacheFilter(setOf("Order"), 0, 100)
        val client = client(subgraph, zero)
        val a = entities("Order", "1")
        repeat(3) { client.post(a) }
        assertEquals(3, subgraph.calls.get())
        assertEquals(0L to 3L, zero.counts)

        val short = EntityCacheFilter(setOf("Order"), 200, 100)
        val client2 = client(subgraph, short)
        client2.post(a); client2.post(a)
        Thread.sleep(300)
        client2.post(a)
        assertEquals(1L to 2L, short.counts)
    }

    @Test
    fun `spans - off none, on cache spans with misarch_cache hit or miss`() {
        val subgraph = Subgraph()
        val client = client(subgraph, EntityCacheFilter(setOf("Order"), 60_000, 100))
        val a = entities("Order", "1")
        client.post(a)
        assertTrue(tracing.spans.isEmpty())
        assertTrue(TimingSpans.enable(tracing.openTelemetry))
        client.post(a)                          // hit
        client.post(entities("Order", "2"))     // miss
        client.post(entities("Product", "2"))   // not a cached type: no span
        val spans = tracing.timingSpans()
        assertEquals(listOf("cache Order", "cache Order"), spans.map { it.name })
        assertEquals(listOf("hit", "miss"), spans.map { it.attributes.get(TimingSpans.CACHE) })
        assertEquals(listOf("Order", "Order"), spans.map { it.attributes.get(TimingSpans.ENTITY) })
        assertFalse(spans.any { it.status.statusCode == io.opentelemetry.api.trace.StatusCode.ERROR })
    }

    @Test
    fun `without the filter the subgraph is called every time`() {
        val subgraph = Subgraph()
        val client = client(subgraph, null)
        repeat(2) { client.post(entities("Order", "1")) }
        assertEquals(2, subgraph.calls.get())
    }
}
