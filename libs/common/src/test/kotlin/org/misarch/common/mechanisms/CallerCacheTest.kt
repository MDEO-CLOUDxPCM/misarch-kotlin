package org.misarch.common.mechanisms

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.misarch.common.timing.TestTracing
import org.misarch.common.timing.TimingSpans
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebHandler
import org.springframework.web.server.handler.FilteringWebHandler
import reactor.core.publisher.Mono
import java.util.concurrent.atomic.AtomicInteger

/** The caller-side cache (docs/MECHANISMS.md): switch, hit/miss, key, what is stored, TTL, counters and spans. */
class CallerCacheTest {

    private val tracing = TestTracing()
    private val calls = AtomicInteger()

    @AfterEach
    fun tearDown() {
        TimingSpans.disable()
        tracing.close()
    }

    private fun CallerCache.get(target: String, body: String, method: String = "graphql", answer: String? = null): String =
        runBlocking {
            lookup(target, method, body.toByteArray(), { r: String -> !r.startsWith("error") }) {
                answer ?: "${target}:${calls.incrementAndGet()}"
            }
        }

    @Test
    fun `off - no bean without targets, cache with targets (property or env name)`() {
        val runner = ApplicationContextRunner().withUserConfiguration(CallerCacheConfiguration::class.java)
        runner.run { assertTrue(it.getBeansOfType(CallerCache::class.java).isEmpty()) }
        runner.withPropertyValues("$CALLER_TARGETS_PROPERTY= ").run {
            assertTrue(it.getBeansOfType(CallerCache::class.java).isEmpty())
        }
        runner.withPropertyValues("$CALLER_TARGETS_PROPERTY=shipment").run {
            assertEquals(setOf("shipment"), it.getBean(CallerCache::class.java).targets)
            assertEquals(1, it.getBeansOfType(WebFilter::class.java).size)
        }
        runner.withPropertyValues("MISARCH_CALLER_CACHE_TARGETS=shipment, discount", "MISARCH_CALLER_CACHE_TTL_SECONDS=5").run {
            assertEquals(setOf("shipment", "discount"), it.getBean(CallerCache::class.java).targets)
        }
    }

    @Test
    fun `hit, miss, key and what is stored`() {
        val cache = CallerCache(setOf("shipment", "discount"), 60_000, 100)
        assertEquals("shipment:1", cache.get("shipment", "a"))
        assertEquals("shipment:1", cache.get("shipment", "a"))            // hit: no call
        assertEquals("shipment:2", cache.get("shipment", "b"))            // other body
        assertEquals("shipment:3", cache.get("shipment", "a", method = "x")) // other method
        assertEquals("discount:4", cache.get("discount", "a"))            // other target
        assertEquals("inventory:5", cache.get("inventory", "a"))          // not covered: always called, not counted
        assertEquals("inventory:6", cache.get("inventory", "a"))
        // answers the caller does not accept are not stored
        assertEquals("error", cache.get("shipment", "c", answer = "error"))
        assertEquals("shipment:7", cache.get("shipment", "c"))
        // failures are not stored and are rethrown
        assertThrows(IllegalStateException::class.java) {
            runBlocking { cache.lookup<String>("shipment", "graphql", "d".toByteArray(), { true }) { error("down") } }
        }
        assertEquals("shipment:8", cache.get("shipment", "d"))
        val counts = cache.counts()
        assertEquals(1L, counts["hits"])
        assertEquals(8L, counts["misses"])
        assertEquals(6, counts["entries"])
        assertEquals(mapOf("discount" to mapOf("hits" to 0L, "misses" to 1L), "shipment" to mapOf("hits" to 1L, "misses" to 7L)),
            counts["byTarget"])
    }

    @Test
    fun `ttl 0 and expiry`() {
        val zero = CallerCache(setOf("shipment"), 0, 100)
        repeat(3) { zero.get("shipment", "a") }
        assertEquals(3, calls.get())
        val short = CallerCache(setOf("shipment"), 200, 100)
        short.get("shipment", "a"); short.get("shipment", "a")
        Thread.sleep(300)
        short.get("shipment", "a")
        assertEquals(5, calls.get())
        assertEquals(1L, short.counts()["hits"])
    }

    @Test
    fun `counter endpoint`() {
        val cache = CallerCache(setOf("shipment"), 60_000, 100)
        cache.get("shipment", "a"); cache.get("shipment", "a")
        val handler = WebHandler { Mono.error(IllegalStateException("not the counters")) }
        val client = WebTestClient.bindToWebHandler(FilteringWebHandler(handler, listOf(CallerCacheCountsFilter(cache))))
            .configureClient().build()
        val body = client.get().uri(CALLER_COUNTS_PATH).exchange().expectStatus().isOk.expectBody(String::class.java)
            .returnResult().responseBody!!
        assertEquals(
            """{"targets":["shipment"],"ttlMs":60000,"hits":1,"misses":1,"entries":1,"byTarget":{"shipment":{"hits":1,"misses":1}}}""",
            body,
        )
    }

    @Test
    fun `spans - off none, on cache spans with misarch_cache hit or miss`() {
        val cache = CallerCache(setOf("shipment"), 60_000, 100)
        cache.get("shipment", "a")
        assertTrue(tracing.spans.isEmpty())
        assertTrue(TimingSpans.enable(tracing.openTelemetry))
        cache.get("shipment", "a")    // hit
        cache.get("shipment", "b")    // miss
        cache.get("inventory", "b")   // not covered: no span
        val spans = tracing.timingSpans()
        assertEquals(listOf("cache shipment", "cache shipment"), spans.map { it.name })
        assertEquals(listOf("hit", "miss"), spans.map { it.attributes.get(TimingSpans.CACHE) })
        assertEquals(listOf("shipment", "shipment"), spans.map { it.attributes.get(TimingSpans.APP) })
        assertEquals(listOf("graphql", "graphql"), spans.map { it.attributes.get(TimingSpans.METHOD) })
    }
}
