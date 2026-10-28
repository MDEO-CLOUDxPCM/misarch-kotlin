package org.misarch.common.mechanisms

import io.opentelemetry.api.trace.Span
import org.misarch.common.timing.TimingSpans
import org.reactivestreams.Publisher
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.ServerHttpRequestDecorator
import org.springframework.http.server.reactive.ServerHttpResponseDecorator
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Mechanism "response cache" (docs/MECHANISMS.md): a response cache for the federation entity fetches (`_entities`)
 * the gateway sends to this subgraph, for the entity types in `misarch.mechanisms.entity-cache-types` (env
 * `MISARCH_ENTITY_CACHE_TYPES`, comma-separated, e.g. `Order` in shipment for orderTracking's Order.shipments,
 * `ProductVariant` in catalog for wishlistView). Off unless the types are set.
 *
 * Key: the request body (query text + variables, i.e. the representations) and the `Authorized-User` header; value:
 * the complete response body, kept for `misarch.mechanisms.entity-cache-ttl-seconds` (env
 * `MISARCH_ENTITY_CACHE_TTL_SECONDS`, default 60; 0 = every lookup misses, but responses are still stored, so a
 * miss costs the same as with a TTL). Only successful responses without
 * `errors` are stored. No invalidation: staleness is bounded by the TTL. At `entity-cache-max-entries` (env
 * `MISARCH_ENTITY_CACHE_MAX_ENTRIES`, default 10000) the cache is cleared.
 *
 * With the timing spans on ([TimingSpans]) every lookup gets a span `cache <Type>` with `misarch.entity` and
 * `misarch.cache=hit|miss` (from the lookup until the response is written).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("'\${$TYPES_PROPERTY:\${MISARCH_ENTITY_CACHE_TYPES:}}'.trim() != ''")
class EntityCacheConfiguration {

    @Bean
    fun entityCacheFilter(
        @Value("\${$TYPES_PROPERTY:\${MISARCH_ENTITY_CACHE_TYPES:}}") types: String,
        @Value("\${$TTL_PROPERTY:\${MISARCH_ENTITY_CACHE_TTL_SECONDS:60}}") ttlSeconds: Long,
        @Value("\${$MAX_ENTRIES_PROPERTY:\${MISARCH_ENTITY_CACHE_MAX_ENTRIES:10000}}") maxEntries: Int,
    ): WebFilter = EntityCacheFilter(
        types.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet(), ttlSeconds * 1000, maxEntries,
    )
}

const val TYPES_PROPERTY = "misarch.mechanisms.entity-cache-types"
const val TTL_PROPERTY = "misarch.mechanisms.entity-cache-ttl-seconds"
const val MAX_ENTRIES_PROPERTY = "misarch.mechanisms.entity-cache-max-entries"

class EntityCacheFilter(private val types: Set<String>, private val ttlMs: Long, private val maxEntries: Int) : WebFilter {
    private val logger = LoggerFactory.getLogger(EntityCacheFilter::class.java)
    private val mapper = JsonMapper.builder().build()
    private val cache = ConcurrentHashMap<String, Pair<Long, ByteArray>>()
    private val hits = AtomicLong()
    private val misses = AtomicLong()

    init {
        logger.info("entity cache on for {} (ttl {} ms, max entries {})", types, ttlMs, maxEntries)
    }

    /** Lookups so far (hits, misses). */
    val counts: Pair<Long, Long> get() = hits.get() to misses.get()

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val request = exchange.request
        if (request.method != HttpMethod.POST) return chain.filter(exchange)
        return DataBufferUtils.join(request.body).defaultIfEmpty(exchange.response.bufferFactory().wrap(ByteArray(0)))
            .flatMap { buffer ->
                val body = ByteArray(buffer.readableByteCount()).also { buffer.read(it) }
                DataBufferUtils.release(buffer)
                val type = cachedEntityType(body)
                val key = if (type != null) {
                    (request.headers.getFirst("Authorized-User") ?: "") + "\u0000" + String(body, Charsets.UTF_8)
                } else null
                val now = System.currentTimeMillis()
                val cached = key?.let { cache[it] }?.takeIf { it.first > now }
                if (cached != null) {
                    count(hits)
                    val span = span(type!!, TimingSpans.CACHE_HIT)
                    val response = exchange.response
                    response.statusCode = HttpStatus.OK
                    response.headers.contentType = MediaType.APPLICATION_JSON
                    response.headers.contentLength = cached.second.size.toLong()
                    return@flatMap traced(span, response.writeWith(Mono.just(response.bufferFactory().wrap(cached.second))))
                }
                val span = if (key != null) {
                    count(misses)
                    span(type!!, TimingSpans.CACHE_MISS)
                } else null
                val replayed = object : ServerHttpRequestDecorator(request) {
                    override fun getBody(): Flux<DataBuffer> =
                        Flux.defer { Flux.just(exchange.response.bufferFactory().wrap(body)) }
                }
                val response = if (key == null) exchange.response else object : ServerHttpResponseDecorator(exchange.response) {
                    override fun writeWith(body: Publisher<out DataBuffer>): Mono<Void> =
                        DataBufferUtils.join(Flux.from(body)).flatMap { out ->
                            val bytes = ByteArray(out.readableByteCount()).also { out.read(it) }
                            DataBufferUtils.release(out)
                            if ((statusCode == null || statusCode == HttpStatus.OK) && !hasErrors(bytes)) {
                                if (cache.size >= maxEntries) cache.clear()
                                cache[key] = (System.currentTimeMillis() + ttlMs) to bytes
                            }
                            super.writeWith(Mono.just(bufferFactory().wrap(bytes)))
                        }
                }
                traced(span, chain.filter(exchange.mutate().request(replayed).response(response).build()))
            }
    }

    /**
     * The entity type if this is a GraphQL `_entities` request whose representations (all `*representations`
     * variables: the gateway names them `representations` or, in batched fetches, `_v<n>_representations`) are all of
     * cached types, else null.
     */
    private fun cachedEntityType(body: ByteArray): String? = try {
        val json = mapper.readTree(body)
        val query = json.get("query")?.asString() ?: ""
        val vars = json.get("variables")
        val reps = vars?.propertyNames()?.filter { it.endsWith("representations") }?.map { vars.get(it) }.orEmpty()
        val names = reps.flatMap { r ->
            if (r.isArray && r.size() > 0) (0 until r.size()).map { r.get(it)?.get("__typename")?.asString() } else listOf(null)
        }
        if (query.contains("_entities") && names.isNotEmpty() && names.all { it in types }) {
            names.filterNotNull().distinct().sorted().joinToString(",")
        } else null
    } catch (e: Exception) {
        null
    }

    private fun hasErrors(bytes: ByteArray): Boolean = try {
        mapper.readTree(bytes).has("errors")
    } catch (e: Exception) {
        true
    }

    private fun span(type: String, result: String): Span? = TimingSpans.start({ "cache $type" }) {
        it.setAttribute(TimingSpans.ENTITY, type)
        it.setAttribute(TimingSpans.CACHE, result)
    }

    /** Ends [span] (if any) when [mono] completes, fails or is cancelled. */
    private fun traced(span: Span?, mono: Mono<Void>): Mono<Void> = if (span == null) mono else mono
        .doOnSuccess { TimingSpans.end(span, null, null) }
        .doOnError { TimingSpans.end(span, null, it) }
        .doOnCancel { TimingSpans.end(span, null, java.util.concurrent.CancellationException()) }

    private fun count(c: AtomicLong) {
        c.incrementAndGet()
        if ((hits.get() + misses.get()) % 200 == 0L) {
            logger.info("entity cache {}: hits {}, misses {}, entries {}", types, hits.get(), misses.get(), cache.size)
        }
    }
}
