package org.misarch.common.timing

import graphql.GraphQLContext
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context
import io.opentelemetry.extension.kotlin.asContextElement
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.misarch.common.event.DaprProperties
import org.misarch.common.event.DaprServiceInvoker
import org.misarch.common.event.EventPublisher
import org.misarch.common.graphql.dataloader.BaseIdDataLoader
import org.misarch.common.testing.FakeDaprSidecar
import org.springframework.web.reactive.function.client.WebClient
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import kotlin.coroutines.CoroutineContext

/** In-memory tracer for the timing span tests (one per test class). */
class TestTracing : AutoCloseable {
    val exporter: InMemorySpanExporter = InMemorySpanExporter.create()
    private val tracerProvider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()
    private val sdk = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build()

    /** Not closeable: an application context must not shut the SDK down when it closes. */
    val openTelemetry: OpenTelemetry = object : OpenTelemetry by sdk {}
    val tracer = openTelemetry.getTracer("test")

    val spans: List<SpanData> get() = exporter.finishedSpanItems

    fun timingSpans(): List<SpanData> = spans.filter { it.instrumentationScopeInfo.name == TimingSpans.INSTRUMENTATION_NAME }

    fun span(name: String): SpanData = timingSpans().single { it.name == name }

    override fun close() = tracerProvider.close()
}

/** The facade and the hooks without a database: data loader, publish, invoke, GraphQL context. */
class TimingSpansTest {

    private val tracing = TestTracing()

    @AfterEach
    fun tearDown() {
        TimingSpans.disable()
        tracing.close()
    }

    @Test
    fun `off - span runs the block only`() = runBlocking {
        assertFalse(TimingSpans.enabled)
        assertEquals(42, TimingSpans.span({ "x" }, {}, { 1L }) { 42 })
        assertTrue(tracing.spans.isEmpty())
    }

    @Test
    fun `no agent - the no-op OpenTelemetry keeps the spans off`() {
        assertFalse(TimingSpans.enable(OpenTelemetry.noop()))
        assertFalse(TimingSpans.enabled)
    }

    @Test
    fun `on - name, attributes, rows, parent and errors`() = runBlocking {
        assertTrue(TimingSpans.enable(tracing.openTelemetry))
        val outer = tracing.tracer.spanBuilder("outer").startSpan()
        withContext(Context.current().with(outer).asContextElement()) {
            val result = TimingSpans.span({ "a" }, { it.setAttribute(TimingSpans.ENTITY, "E") }, { r: List<Int> -> r.size.toLong() }) {
                // the block runs with the span as current context
                TimingSpans.span({ "b" }, {}, { null }) { listOf(1, 2, 3) }
            }
            assertEquals(listOf(1, 2, 3), result)
        }
        outer.end()
        val a = tracing.span("a")
        assertEquals("E", a.attributes.get(TimingSpans.ENTITY))
        assertEquals(3L, a.attributes.get(TimingSpans.ROWS))
        assertEquals(outer.spanContext.spanId, a.parentSpanId)
        assertEquals(a.spanId, tracing.span("b").parentSpanId)

        val error = IllegalStateException("boom")
        val thrown = assertThrows<IllegalStateException> { TimingSpans.span({ "c" }, {}, { 1L }) { throw error } }
        // the same exception (with -ea, kotlinx-coroutines' stack trace recovery rethrows a copy caused by it)
        assertTrue(thrown === error || thrown.cause === error)
        val c = tracing.span("c")
        assertEquals(StatusCode.ERROR, c.status.statusCode)
        assertEquals(IllegalStateException::class.java.name, c.attributes.get(TimingSpans.ERROR_TYPE))
        assertEquals(null, c.attributes.get(TimingSpans.ROWS))
    }

    @Test
    fun `instrumentation errors never escape`() = runBlocking {
        TimingSpans.enable(tracing.openTelemetry)
        assertEquals(7, TimingSpans.span({ "d" }, { throw IllegalArgumentException("attributes") }, { 1L }) { 7 })
        assertEquals(8, TimingSpans.span({ "e" }, {}, { error("rows") }) { 8 })
        assertEquals(listOf("e"), tracing.timingSpans().map { it.name })
    }

    @Test
    fun `GraphQL context - unchanged while off, trace context entry while on`() {
        val context = GraphQLContext.newContext().of("k", "v").build()
        assertSame(context, TimingSpans.withTraceContext(context))
        TimingSpans.enable(tracing.openTelemetry)
        val withTrace = TimingSpans.withTraceContext(context)
        assertEquals("v", withTrace.get<String>("k"))
        assertTrue(withTrace.hasKey(CoroutineContext::class))
    }

    private class Loader : BaseIdDataLoader<String, UUID>() {
        override suspend fun loadAll(ids: List<UUID>): List<UUID> = ids.filter { it.leastSignificantBits == 1L }
        override fun toDTO(entity: UUID): String = entity.toString()
        override fun key(entity: UUID): UUID = entity
    }

    private fun loadTwo(): List<String?> {
        val loader = Loader().getDataLoader(GraphQLContext.newContext().build())
        val a = loader.load(UUID(0, 1))
        val b = loader.load(UUID(0, 2))
        loader.dispatch()
        return listOf(a.get(), runCatching { b.get() }.exceptionOrNull()?.cause?.javaClass?.name)
    }

    @Test
    fun `data loader - off no spans, on dataloader span with batch size and rows, same values`() {
        val off = loadTwo()
        assertTrue(tracing.spans.isEmpty())
        TimingSpans.enable(tracing.openTelemetry)
        assertEquals(off, loadTwo())
        val span = tracing.span("dataloader UUID")
        assertEquals("UUID", span.attributes.get(TimingSpans.ENTITY))
        assertEquals(TimingSpans.READ, span.attributes.get(TimingSpans.ACCESS))
        assertEquals(2L, span.attributes.get(TimingSpans.BATCH_SIZE))
        assertEquals(1L, span.attributes.get(TimingSpans.ROWS))
    }

    @Test
    fun `publish and invoke - off no spans, on logical spans, same requests and responses`() = runBlocking {
        FakeDaprSidecar().use { dapr ->
            dapr.stubInvocation("catalog", "graphql") { FakeDaprSidecar.StubResponse(body = """{"data":{"x":1}}""") }
            val properties = DaprProperties(httpEndpoint = dapr.endpoint)
            val publisher = EventPublisher(WebClient.builder().build(), properties)
            val invoker = DaprServiceInvoker(WebClient.builder().build(), properties, JsonMapper.builder().build())
            suspend fun calls(): List<Any> {
                publisher.publishEvent("a/b/created", mapOf("id" to "1"))
                val response = invoker.graphql("catalog", "{ x }")
                return listOf(dapr.awaitPublished("a/b/created").last().body, response.toString(), dapr.invocations.last().body)
            }

            val off = calls()
            assertTrue(tracing.spans.isEmpty())
            TimingSpans.enable(tracing.openTelemetry)
            assertEquals(off, calls())
            val publish = tracing.span("publish a/b/created")
            assertEquals("a/b/created", publish.attributes.get(TimingSpans.TOPIC))
            assertEquals("pubsub", publish.attributes.get(TimingSpans.PUBSUB))
            val invoke = tracing.span("invoke catalog.graphql")
            assertEquals("catalog", invoke.attributes.get(TimingSpans.APP))
            assertEquals("graphql", invoke.attributes.get(TimingSpans.METHOD))
        }
    }
}
