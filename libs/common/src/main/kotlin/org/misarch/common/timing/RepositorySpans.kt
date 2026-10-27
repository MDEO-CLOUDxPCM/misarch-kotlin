package org.misarch.common.timing

import io.opentelemetry.api.trace.Span
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.aopalliance.intercept.MethodInterceptor
import org.aopalliance.intercept.MethodInvocation
import org.reactivestreams.Publisher
import org.springframework.aop.framework.ProxyFactory
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.core.KotlinDetector
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.support.R2dbcRepositoryFactoryBean
import org.springframework.data.repository.core.RepositoryInformation
import org.springframework.data.repository.core.support.RepositoryProxyPostProcessor
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.lang.reflect.Method
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Adds [RepositorySpans] to every Spring Data R2DBC repository (`CoroutineCrudRepository`, `R2dbcRepository`) of
 * the service: a customizer on each [R2dbcRepositoryFactoryBean], before the repository proxy is created.
 * Registered by [TimingSpansConfiguration] only if the spans are switched on.
 */
class TimingRepositoryPostProcessor : BeanPostProcessor {

    override fun postProcessBeforeInitialization(bean: Any, beanName: String): Any {
        if (bean is R2dbcRepositoryFactoryBean<*, *, *>) {
            bean.addRepositoryFactoryCustomizer { factory -> factory.addRepositoryProxyPostProcessor(PROXY_POST_PROCESSOR) }
        }
        return bean
    }

    companion object {
        private val PROXY_POST_PROCESSOR = RepositoryProxyPostProcessor { factory: ProxyFactory, info: RepositoryInformation ->
            factory.addAdvice(RepositorySpans(info.domainType.simpleName))
        }
    }
}

/**
 * Wraps each repository call in a span `repo <Entity>.<method>` (`misarch.entity`, `misarch.method`,
 * `misarch.access`, `misarch.rows`). Writes are `@Modifying` queries and methods named `save*`, `delete*`,
 * `update*`, `insert*`, `upsert*`, `create*`, `remove*`. The span lasts from the subscription (collection) of the
 * query until its completion, error or cancellation, and is the current context while the query is subscribed:
 * - `suspend` methods: Spring's AOP proxy runs the advice chain with the reactive result (`Mono`) and awaits it
 *   only after the chain, so they are handled like `Mono` results;
 * - `Flow`, `Mono`, `Flux` results;
 * - anything else: from call to return.
 */
internal class RepositorySpans(private val entity: String) : MethodInterceptor {

    private enum class Kind { REACTIVE, FLOW, PLAIN, SKIP }

    private class Info(val method: String, val access: String, val kind: Kind)

    private val infos = ConcurrentHashMap<Method, Info>()

    @Suppress("UNCHECKED_CAST")
    override fun invoke(invocation: MethodInvocation): Any? {
        if (!TimingSpans.enabled) return invocation.proceed()
        val info = infos.computeIfAbsent(invocation.method, ::describe)
        return when (info.kind) {
            Kind.SKIP -> invocation.proceed()
            Kind.REACTIVE -> when (val result = invocation.proceed()) {
                is Mono<*> -> traced(result as Mono<Any>, info)
                is Flux<*> -> traced(result as Flux<Any>, info)
                else -> result
            }
            Kind.FLOW -> (invocation.proceed() as Flow<Any?>?)?.let { traced(it, info) }
            Kind.PLAIN -> invokePlain(invocation, info)
        }
    }

    private fun start(info: Info): Span? = TimingSpans.start({ "repo $entity.${info.method}" }) {
        it.setAttribute(TimingSpans.ENTITY, entity)
            .setAttribute(TimingSpans.METHOD, info.method)
            .setAttribute(TimingSpans.ACCESS, info.access)
    }

    private fun invokePlain(invocation: MethodInvocation, info: Info): Any? {
        val span = start(info) ?: return invocation.proceed()
        val scope = TimingSpans.makeCurrent(span)
        val result = try {
            invocation.proceed()
        } catch (e: Throwable) {
            TimingSpans.end(span, null, e)
            throw e
        } finally {
            scope?.close()
        }
        TimingSpans.end(span, rows(result, info), null)
        return result
    }

    private fun <T> traced(source: Flow<T>, info: Info): Flow<T> = flow {
        val span = start(info)
        if (span == null) {
            source.collect { emit(it) }
            return@flow
        }
        var rows = 0L
        val element = TimingSpans.contextElement(span)
        try {
            (if (element == null) source else source.flowOn(element)).collect {
                rows++
                emit(it)
            }
        } catch (e: Throwable) {
            TimingSpans.end(span, rows, e)
            throw e
        }
        TimingSpans.end(span, rows, null)
    }

    private fun <T : Any> traced(source: Mono<T>, info: Info): Mono<T> = Mono.defer {
        val span = start(info) ?: return@defer source
        val rows = AtomicLong()
        Mono.fromDirect(subscribedIn(span, source))
            .doOnNext { rows.set(rows(it, info) ?: 1L) }
            .doOnError { TimingSpans.end(span, null, it) }
            .doOnSuccess { TimingSpans.end(span, rows.get(), null) }
            .doOnCancel { TimingSpans.end(span, rows.get(), CancellationException("cancelled")) }
    }

    private fun <T : Any> traced(source: Flux<T>, info: Info): Flux<T> = Flux.defer {
        val span = start(info) ?: return@defer source
        val rows = AtomicLong()
        Flux.from(subscribedIn(span, source))
            .doOnNext { rows.incrementAndGet() }
            .doOnError { TimingSpans.end(span, rows.get(), it) }
            .doOnComplete { TimingSpans.end(span, rows.get(), null) }
            .doOnCancel { TimingSpans.end(span, rows.get(), CancellationException("cancelled")) }
    }

    /**
     * [source], subscribed with [span] as current context (the agent's R2DBC/Reactor instrumentation picks it up as
     * parent). The subscriber is passed on unchanged, so its Reactor context (transactions) stays the same.
     */
    private fun <T : Any> subscribedIn(span: Span, source: Publisher<T>): Publisher<T> = Publisher { subscriber ->
        val scope = TimingSpans.makeCurrent(span)
        try {
            source.subscribe(subscriber)
        } finally {
            scope?.close()
        }
    }

    private fun describe(method: Method): Info {
        val kind = when {
            method.declaringClass == Any::class.java -> Kind.SKIP
            KotlinDetector.isSuspendingFunction(method) -> Kind.REACTIVE
            Flow::class.java.isAssignableFrom(method.returnType) -> Kind.FLOW
            Mono::class.java.isAssignableFrom(method.returnType) -> Kind.REACTIVE
            Flux::class.java.isAssignableFrom(method.returnType) -> Kind.REACTIVE
            Publisher::class.java.isAssignableFrom(method.returnType) -> Kind.SKIP
            else -> Kind.PLAIN
        }
        val write = method.isAnnotationPresent(Modifying::class.java) ||
            WRITE_PREFIXES.any { method.name.startsWith(it) }
        return Info(method.name, if (write) TimingSpans.WRITE else TimingSpans.READ, kind)
    }

    /** Rows returned or written: collection size, update count of a write, 0 for null/Unit, else 1. */
    private fun rows(result: Any?, info: Info): Long? = when (result) {
        null, Unit -> 0L
        is Collection<*> -> result.size.toLong()
        is Number -> if (info.access == TimingSpans.WRITE) result.toLong() else 1L
        else -> 1L
    }

    companion object {
        private val WRITE_PREFIXES = listOf("save", "delete", "update", "insert", "upsert", "create", "remove")
    }
}
