package org.misarch.common.mechanisms

import io.opentelemetry.api.trace.Span
import org.misarch.common.timing.TimingSpans
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Mechanism "caller-side cache" (docs/MECHANISMS.md): the caller of a synchronous Dapr service invocation keeps the
 * provider's answer, so that a hit skips the whole remote call (the caller's sidecar, the provider's sidecars, the
 * provider and its database). Used by [org.misarch.common.event.DaprServiceInvoker] (order's createOrder invocations).
 *
 * Switch: `misarch.mechanisms.caller-cache-targets` (env `MISARCH_CALLER_CACHE_TARGETS`), comma-separated target app
 * ids (e.g. `shipment,discount`); off unless set. Key: target app + method + request body (the same for all users;
 * a body that names the user, like discount's `userId`, is per user by itself). Value: the parsed response. TTL
 * `misarch.mechanisms.caller-cache-ttl-seconds` (env `MISARCH_CALLER_CACHE_TTL_SECONDS`, default 60; 0 = every lookup
 * misses, responses are still stored), max entries `misarch.mechanisms.caller-cache-max-entries` (env
 * `MISARCH_CALLER_CACHE_MAX_ENTRIES`, default 10000; the cache is cleared when it is full). Only answers the caller
 * accepts ([CallerCache.lookup]'s `cacheable`) are stored. No invalidation: staleness is bounded by the TTL.
 *
 * With the timing spans on, every lookup gets a span `cache <target>` with `misarch.app`, `misarch.method` and
 * `misarch.cache=hit|miss` (a miss's span lasts until the remote answer is there). `GET /mechanisms/caller-cache`
 * answers the counters since start (`{"targets", "ttlMs", "hits", "misses", "entries", "byTarget"}`).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("'\${$CALLER_TARGETS_PROPERTY:\${MISARCH_CALLER_CACHE_TARGETS:}}'.trim() != ''")
class CallerCacheConfiguration {

    @Bean
    fun callerCache(
        @Value("\${$CALLER_TARGETS_PROPERTY:\${MISARCH_CALLER_CACHE_TARGETS:}}") targets: String,
        @Value("\${$CALLER_TTL_PROPERTY:\${MISARCH_CALLER_CACHE_TTL_SECONDS:60}}") ttlSeconds: Long,
        @Value("\${$CALLER_MAX_ENTRIES_PROPERTY:\${MISARCH_CALLER_CACHE_MAX_ENTRIES:10000}}") maxEntries: Int,
    ): CallerCache = CallerCache(
        targets.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet(), ttlSeconds * 1000, maxEntries,
    )

    @Bean
    fun callerCacheCountsFilter(cache: CallerCache): WebFilter = CallerCacheCountsFilter(cache)
}

const val CALLER_TARGETS_PROPERTY = "misarch.mechanisms.caller-cache-targets"
const val CALLER_TTL_PROPERTY = "misarch.mechanisms.caller-cache-ttl-seconds"
const val CALLER_MAX_ENTRIES_PROPERTY = "misarch.mechanisms.caller-cache-max-entries"
const val CALLER_COUNTS_PATH = "/mechanisms/caller-cache"

class CallerCache(val targets: Set<String>, private val ttlMs: Long, private val maxEntries: Int) {
    private val logger = LoggerFactory.getLogger(CallerCache::class.java)
    private val cache = ConcurrentHashMap<String, Pair<Long, Any>>()
    private val counters = ConcurrentHashMap<String, Pair<AtomicLong, AtomicLong>>()
    private val lookups = AtomicLong()

    init {
        logger.info("caller cache on for {} (ttl {} ms, max entries {})", targets, ttlMs, maxEntries)
    }

    /** Whether invocations of [target] go through the cache. */
    fun covers(target: String): Boolean = target in targets

    /**
     * The cached answer of [target]'s [method] for [body], else [call]'s, which is stored if [cacheable] accepts it.
     * For a target that is not covered this is just [call].
     */
    suspend fun <T : Any> lookup(
        target: String,
        method: String,
        body: ByteArray,
        cacheable: (T) -> Boolean,
        call: suspend () -> T,
    ): T {
        if (!covers(target)) return call()
        val key = target + "\u0000" + method + "\u0000" + String(body, Charsets.UTF_8)
        val now = System.currentTimeMillis()
        val cached = cache[key]?.takeIf { it.first > now }
        val (hits, misses) = counters.computeIfAbsent(target) { AtomicLong() to AtomicLong() }
        if (cached != null) {
            hits.incrementAndGet()
            counted()
            span(target, method, TimingSpans.CACHE_HIT)?.let { TimingSpans.end(it, null, null) }
            @Suppress("UNCHECKED_CAST")
            return cached.second as T
        }
        misses.incrementAndGet()
        counted()
        val span = span(target, method, TimingSpans.CACHE_MISS)
        val result = try {
            call()
        } catch (e: Throwable) {
            span?.let { TimingSpans.end(it, null, e) }
            throw e
        }
        span?.let { TimingSpans.end(it, null, null) }
        if (cacheable(result)) {
            if (cache.size >= maxEntries) cache.clear()
            cache[key] = (System.currentTimeMillis() + ttlMs) to result
        }
        return result
    }

    /** Counters since start, as served at [CALLER_COUNTS_PATH]. */
    fun counts(): Map<String, Any> {
        val byTarget = targets.sorted().associateWith { t ->
            val c = counters[t]
            mapOf("hits" to (c?.first?.get() ?: 0L), "misses" to (c?.second?.get() ?: 0L))
        }
        return linkedMapOf(
            "targets" to targets.sorted(), "ttlMs" to ttlMs,
            "hits" to byTarget.values.sumOf { it["hits"]!! }, "misses" to byTarget.values.sumOf { it["misses"]!! },
            "entries" to cache.size, "byTarget" to byTarget,
        )
    }

    private fun span(target: String, method: String, result: String): Span? =
        TimingSpans.start({ "cache $target" }) {
            it.setAttribute(TimingSpans.APP, target)
            it.setAttribute(TimingSpans.METHOD, method)
            it.setAttribute(TimingSpans.CACHE, result)
        }

    private fun counted() {
        if (lookups.incrementAndGet() % 200 == 0L) {
            logger.info("caller cache {}: {}", targets, counts())
        }
    }
}

/** Serves `GET /mechanisms/caller-cache`. */
class CallerCacheCountsFilter(private val cache: CallerCache) : WebFilter {
    private val mapper = JsonMapper.builder().build()

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val request = exchange.request
        if (request.method != HttpMethod.GET || request.path.value() != CALLER_COUNTS_PATH) return chain.filter(exchange)
        val body = mapper.writeValueAsBytes(cache.counts())
        val response = exchange.response
        response.statusCode = HttpStatus.OK
        response.headers.contentType = MediaType.APPLICATION_JSON
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)))
    }
}
