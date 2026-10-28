package org.misarch.common.timing.counters

import graphql.execution.instrumentation.InstrumentationState
import graphql.execution.instrumentation.SimplePerformantInstrumentation
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLObjectType
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.Dispatchers
import org.reactivestreams.Publisher
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.ServerHttpResponseDecorator
import org.springframework.web.method.HandlerMethod
import org.springframework.web.reactive.HandlerMapping
import org.springframework.web.reactive.function.server.RouterFunctions
import org.springframework.web.server.CoWebFilter
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder
import kotlin.coroutines.CoroutineContext

/**
 * CPU counters ([CpuCounters], docs/TIMING-COUNTERS.md), only with `misarch.timing.counters=true` (env
 * `MISARCH_TIMING_COUNTERS`); when that is not set, they follow the timing spans switch `misarch.timing.spans` (env
 * `MISARCH_TIMING_SPANS`). Registers the HTTP/event filter with `GET /timing/counters`, the GraphQL data fetcher
 * instrumentation and the R2DBC/converter wrapping. Without the switch nothing of this exists (and `GET
 * /timing/counters` answers 404, as on `main`).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("'\${$COUNTERS_PROPERTY:\${misarch.timing.spans:false}}'.trim() == 'true'")
class TimingCountersConfiguration {

    @Bean
    fun timingCountersActivation(): TimingCountersActivation = TimingCountersActivation()

    @Bean
    fun timingCountersWebFilter(): WebFilter = TimingCountersWebFilter()

    @Bean
    fun timingCountersInstrumentation(): CountingInstrumentation = CountingInstrumentation()

    companion object {
        @Bean
        @JvmStatic
        fun countingR2dbcPostProcessor(): BeanPostProcessor = CountingR2dbcPostProcessor()
    }
}

const val COUNTERS_PROPERTY = "misarch.timing.counters"
const val COUNTERS_PATH = "/timing/counters"

class TimingCountersActivation : DisposableBean {
    init {
        val on = CpuCounters.enable()
        LoggerFactory.getLogger(CpuCounters::class.java).info(
            if (on) "timing counters on" else "timing counters requested, but no thread CPU clock: counters stay off"
        )
    }

    override fun destroy() = CpuCounters.disable()
}

/**
 * One operation per request: `event` (Dapr subscription route, by topic) or `http` (method + route pattern). The
 * request's coroutines carry a [RequestCpuElement] (through the exchange attribute [CoWebFilter.COROUTINE_CONTEXT_ATTRIBUTE],
 * which WebFlux uses for `suspend` controller methods and `coRouter` handlers; with `Dispatchers.Unconfined`, the
 * dispatcher WebFlux uses without the attribute). Also serves `GET /timing/counters` and learns the route -> topic
 * map from the app's own `GET /dapr/subscribe` answers (which the sidecar fetches at start).
 */
class TimingCountersWebFilter : WebFilter, Ordered {
    private val mapper = JsonMapper.builder().build()

    /** Route -> topic(s), from `/dapr/subscribe`. */
    private val topics = ConcurrentHashMap<String, String>()

    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE

    private class RequestAccount : Account {
        val cpuNs = LongAdder()
        override fun add(ns: Long) = cpuNs.add(ns)
    }

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val request = exchange.request
        val path = request.path.value()
        if (request.method == HttpMethod.GET && path == COUNTERS_PATH) return writeCounters(exchange)
        if (!CpuCounters.enabled) return chain.filter(exchange)
        val account = RequestAccount()
        val existing = exchange.attributes[CoWebFilter.COROUTINE_CONTEXT_ATTRIBUTE] as CoroutineContext?
        val base = existing?.let { if (it[ContinuationInterceptor] == null) Dispatchers.Unconfined + it else it }
            ?: Dispatchers.Unconfined
        exchange.attributes[CoWebFilter.COROUTINE_CONTEXT_ATTRIBUTE] = base + RequestCpuElement(account)
        val target = if (request.method == HttpMethod.GET && path == "/dapr/subscribe") learnTopics(exchange) else exchange
        val mono = CpuCounters.explicit(account) { chain.filter(target) }
        return mono.doFinally { flush(exchange, account) }
    }

    private fun flush(exchange: ServerWebExchange, account: RequestAccount) {
        val pattern = (exchange.getAttribute<Any>(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)
            ?: exchange.getAttribute<Any>(RouterFunctions.MATCHING_PATTERN_ATTRIBUTE))?.toString()
            ?: exchange.request.path.value()
        val handler = exchange.getAttribute<Any>(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE) as? HandlerMethod
        val topic = topics[pattern]
            ?: handler?.getMethodAnnotation(io.dapr.Topic::class.java)?.name
        val op = if (topic != null && exchange.request.method == HttpMethod.POST) CpuCounters.operation("event", topic)
        else CpuCounters.operation("http", "${exchange.request.method.name()} $pattern")
        op.calls.increment()
        op.cpuNs.add(account.cpuNs.sum())
    }

    private fun writeCounters(exchange: ServerWebExchange): Mono<Void> {
        val body = mapper.writeValueAsBytes(CpuCounters.snapshot())
        val response = exchange.response
        response.statusCode = HttpStatus.OK
        response.headers.contentType = MediaType.APPLICATION_JSON
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)))
    }

    /** Tees the `/dapr/subscribe` answer into [topics] (route -> topic; several topics on one route: joined by `|`). */
    private fun learnTopics(exchange: ServerWebExchange): ServerWebExchange {
        val response = object : ServerHttpResponseDecorator(exchange.response) {
            override fun writeWith(body: Publisher<out DataBuffer>): Mono<Void> =
                DataBufferUtils.join(Flux.from(body)).flatMap { buffer ->
                    val bytes = ByteArray(buffer.readableByteCount()).also { buffer.read(it) }
                    DataBufferUtils.release(buffer)
                    try {
                        parseSubscriptions(bytes)
                    } catch (e: Exception) {
                        // not a subscription list: nothing learned
                    }
                    super.writeWith(Mono.just(bufferFactory().wrap(bytes)))
                }
        }
        return exchange.mutate().response(response).build()
    }

    internal fun parseSubscriptions(bytes: ByteArray) {
        val byRoute = HashMap<String, MutableSet<String>>()
        for (s in mapper.readTree(bytes)) {
            val topic = s.get("topic")?.asString() ?: continue
            val routes = mutableListOf<String>()
            s.get("route")?.asString()?.let { routes += it }
            s.get("routes")?.let { r ->
                r.get("default")?.asString()?.let { routes += it }
                r.get("rules")?.forEach { rule -> rule.get("path")?.asString()?.let { routes += it } }
            }
            for (route in routes) byRoute.getOrPut(if (route.startsWith("/")) route else "/$route") { sortedSetOf() } += topic
        }
        for ((route, t) in byRoute) topics[route] = t.joinToString("|")
    }
}

/**
 * Every non-trivial data fetcher runs in an explicit frame of its operation (`ParentType.field`, kind `graphql`;
 * `Query._entities` as `_entities.<Type>` of the representations' types, kind `entities`); the coroutine it starts
 * (graphql-kotlin's `scope.future`) is bound to that operation by [RequestCpuElement]. Trivial (property) fetchers
 * are left alone: their CPU goes to the enclosing operation.
 */
class CountingInstrumentation : SimplePerformantInstrumentation() {

    private val byField = ConcurrentHashMap<GraphQLFieldDefinition, Pair<GraphQLObjectType, OpCounter>>()

    override fun instrumentDataFetcher(
        dataFetcher: DataFetcher<*>,
        parameters: InstrumentationFieldFetchParameters,
        state: InstrumentationState?,
    ): DataFetcher<*> {
        if (!CpuCounters.enabled || parameters.isTrivialDataFetcher) return dataFetcher
        return DataFetcher { env ->
            val op = operation(env)
            op.calls.increment()
            CpuCounters.explicit(op) { dataFetcher.get(env) }
        }
    }

    private fun operation(env: DataFetchingEnvironment): OpCounter {
        val field = env.fieldDefinition
        val type = env.executionStepInfo.objectType ?: return CpuCounters.operation("graphql", "?.${field.name}")
        if (field.name == "_entities") {
            @Suppress("UNCHECKED_CAST")
            val reps = env.getArgument<Any>("representations") as? List<Any?>
            val types = reps?.mapNotNull { (it as? Map<*, *>)?.get("__typename") as? String }?.distinct()?.sorted()
            return CpuCounters.operation("entities", "_entities." + (types?.joinToString("+")?.ifEmpty { "?" } ?: "?"))
        }
        val cached = byField[field]
        if (cached != null && cached.first === type) return cached.second
        val op = CpuCounters.operation("graphql", "${type.name}.${field.name}")
        byField.putIfAbsent(field, type to op)
        return op
    }
}
