package org.misarch.common.timing

import com.expediagroup.graphql.generator.extensions.plus
import graphql.GraphQLContext
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanBuilder
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import io.opentelemetry.context.Scope
import io.opentelemetry.extension.kotlin.asContextElement
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Manual spans of the timing campaign (`dataloader <Entity>`, `connection <Entity>`, `repo <Entity>.<method>`,
 * `publish <topic>`, `invoke <app>.<method>`): they name the entity class and the access kind where the agent's
 * R2DBC/WebClient spans only show SQL and URLs.
 *
 * Off by default. `misarch.timing.spans=true` (env `MISARCH_TIMING_SPANS`) makes [TimingSpansConfiguration] call
 * [enable] with the OpenTelemetry of the Java agent (`GlobalOpenTelemetry`, which the agent bridges to its SDK).
 * Without an agent that is the no-op implementation and the spans stay off. While off, every helper runs its block
 * directly (no allocation, no context switch). An error of the instrumentation itself is logged once and never
 * escapes; errors of the block are recorded on the span and rethrown unchanged.
 */
object TimingSpans {

    /** The switch (env `MISARCH_TIMING_SPANS`). */
    const val PROPERTY = "misarch.timing.spans"

    /** Instrumentation scope name of the spans. */
    const val INSTRUMENTATION_NAME = "org.misarch.timing"

    val ENTITY: AttributeKey<String> = AttributeKey.stringKey("misarch.entity")
    val ACCESS: AttributeKey<String> = AttributeKey.stringKey("misarch.access")
    val METHOD: AttributeKey<String> = AttributeKey.stringKey("misarch.method")
    val QUERY: AttributeKey<String> = AttributeKey.stringKey("misarch.query")
    val ROWS: AttributeKey<Long> = AttributeKey.longKey("misarch.rows")
    val BATCH_SIZE: AttributeKey<Long> = AttributeKey.longKey("misarch.batch.size")
    val TOPIC: AttributeKey<String> = AttributeKey.stringKey("misarch.topic")
    val PUBSUB: AttributeKey<String> = AttributeKey.stringKey("misarch.pubsub")
    val APP: AttributeKey<String> = AttributeKey.stringKey("misarch.app")
    val ERROR_TYPE: AttributeKey<String> = AttributeKey.stringKey("error.type")
    val CANCELLED: AttributeKey<Boolean> = AttributeKey.booleanKey("misarch.cancelled")

    const val READ = "read"
    const val WRITE = "write"

    private val logger = LoggerFactory.getLogger(TimingSpans::class.java)
    private val warned = AtomicBoolean()

    @Volatile
    @PublishedApi
    internal var tracer: Tracer? = null

    /** Whether spans are recorded. */
    val enabled: Boolean get() = tracer != null

    /**
     * Records spans with [openTelemetry] from now on, unless it is the no-op implementation (no agent/SDK).
     * Returns whether spans are on.
     */
    fun enable(openTelemetry: OpenTelemetry): Boolean {
        val candidate = safely { openTelemetry.getTracer(INSTRUMENTATION_NAME) } ?: return false
        // the no-op API (no agent) creates invalid span contexts; a probe span is never ended, so never exported
        val valid = safely { candidate.spanBuilder("probe").setNoParent().startSpan().spanContext.isValid } ?: false
        tracer = if (valid) candidate else null
        return valid
    }

    /** Switches the spans off again. */
    fun disable() {
        tracer = null
    }

    /**
     * For the GraphQL context factories: adds the current OpenTelemetry context as `CoroutineContext` entry, which
     * graphql-kotlin adds to the request's coroutine scope, so that the coroutines of the data fetchers and data
     * loaders carry the request's trace. While off [context] is returned unchanged.
     */
    fun withTraceContext(context: GraphQLContext): GraphQLContext {
        if (tracer == null) return context
        val element = safely { Context.current().asContextElement() } ?: return context
        return context + mapOf<Any, Any>(CoroutineContext::class to element)
    }

    /**
     * Runs [block] in a span named [name] (child of the current context; the block runs with the span as current
     * context). [attributes] are set before the start, [rows] gives `misarch.rows` from the result (null: none).
     * While off this is just `block()`.
     */
    internal suspend inline fun <R> span(
        name: () -> String,
        attributes: (SpanBuilder) -> Unit,
        crossinline rows: (R) -> Long?,
        crossinline block: suspend () -> R,
    ): R {
        val tracer = tracer ?: return block()
        val span = start(tracer, name, attributes) ?: return block()
        return within(span, { rows(it) }, { block() })
    }

    /** Starts a span, or null (off or failed). */
    internal inline fun start(name: () -> String, attributes: (SpanBuilder) -> Unit): Span? {
        val tracer = tracer ?: return null
        return start(tracer, name, attributes)
    }

    @PublishedApi
    internal inline fun start(tracer: Tracer, name: () -> String, attributes: (SpanBuilder) -> Unit): Span? =
        try {
            tracer.spanBuilder(name()).also(attributes).startSpan()
        } catch (e: Throwable) {
            failed(e)
            null
        }

    @PublishedApi
    internal suspend fun <R> within(span: Span, rows: (R) -> Long?, block: suspend () -> R): R {
        val element = contextElement(span)
        val result = try {
            if (element == null) block() else withContext(element) { block() }
        } catch (e: Throwable) {
            end(span, null, e)
            throw e
        }
        end(span, safely { rows(result) }, null)
        return result
    }

    /** [span] as coroutine context element (for `withContext`/`flowOn`), or null if that fails. */
    internal fun contextElement(span: Span): CoroutineContext? =
        safely { Context.current().with(span).asContextElement() }

    /** Makes [span] the current context of this thread, or null if that fails. */
    internal fun makeCurrent(span: Span): Scope? = safely { span.makeCurrent() }

    /** Ends [span] with `misarch.rows` (if known) and the error (cancellations are not errors). */
    @PublishedApi
    internal fun end(span: Span, rows: Long?, error: Throwable?) {
        try {
            if (rows != null) span.setAttribute(ROWS, rows)
            if (error is CancellationException) {
                span.setAttribute(CANCELLED, true)
            } else if (error != null) {
                span.setStatus(StatusCode.ERROR)
                span.setAttribute(ERROR_TYPE, error.javaClass.name)
            }
            span.end()
        } catch (e: Throwable) {
            failed(e)
        }
    }

    @PublishedApi
    internal inline fun <T> safely(block: () -> T): T? =
        try {
            block()
        } catch (e: Throwable) {
            failed(e)
            null
        }

    @PublishedApi
    internal fun failed(e: Throwable) {
        if (e is VirtualMachineError) throw e
        if (warned.compareAndSet(false, true)) logger.warn("timing span instrumentation failed (logged once)", e)
    }
}
