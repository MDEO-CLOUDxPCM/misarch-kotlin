package org.misarch.common.testing

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A fake Dapr sidecar HTTP API for tests (JDK HTTP server on a random port):
 * - `POST /v1.0/publish/{pubsub}/{topic...}` is recorded ([published]) and answered with 204,
 * - `/v1.0/invoke/{appId}/method/{path...}` is answered from [stubInvocation] stubs (404 if none) and recorded.
 *
 * Point the service at it with `misarch.dapr.http-endpoint` = [endpoint] (see [MisarchIntegrationTest]).
 */
class FakeDaprSidecar : AutoCloseable {

    /** A published event as received by the sidecar. */
    data class PublishedEvent(val pubsubName: String, val topic: String, val body: String, val contentType: String?) {
        /** The body parsed as JSON. */
        val json: JsonNode get() = mapper.readTree(body)
    }

    /** A service invocation as received by the sidecar. */
    data class Invocation(val appId: String, val path: String, val method: String, val headers: Map<String, String>, val body: String)

    /** A stubbed answer of a service invocation. */
    data class StubResponse(val status: Int = 200, val body: String = "", val contentType: String = "application/json")

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val stubs = ConcurrentHashMap<String, (Invocation) -> StubResponse>()

    val published: MutableList<PublishedEvent> = CopyOnWriteArrayList()
    val invocations: MutableList<Invocation> = CopyOnWriteArrayList()

    /** Status code answered to publish requests (e.g. 500 to test failures). */
    @Volatile
    var publishStatus: Int = 204

    val port: Int get() = server.address.port
    val endpoint: String get() = "http://127.0.0.1:$port"

    init {
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
    }

    /** Stubs `/v1.0/invoke/[appId]/method/[path]` (path without leading slash). */
    fun stubInvocation(appId: String, path: String, response: (Invocation) -> StubResponse) {
        stubs["$appId/${path.trimStart('/')}"] = response
    }

    fun published(topic: String): List<PublishedEvent> = published.filter { it.topic == topic }

    /** Waits until at least [count] events were published to [topic] (async publishers) and returns them. */
    fun awaitPublished(topic: String, count: Int = 1, timeout: Duration = Duration.ofSeconds(10)): List<PublishedEvent> {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (published(topic).size < count && System.nanoTime() < deadline) {
            Thread.sleep(20)
        }
        return published(topic)
    }

    fun reset() {
        published.clear()
        invocations.clear()
        stubs.clear()
        publishStatus = 204
    }

    override fun close() = server.stop(0)

    private fun handle(exchange: HttpExchange) {
        exchange.use {
            val path = exchange.requestURI.rawPath
            val body = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
            when {
                path.startsWith(PUBLISH_PREFIX) && exchange.requestMethod == "POST" -> {
                    val rest = path.removePrefix(PUBLISH_PREFIX)
                    val pubsub = rest.substringBefore('/')
                    val topic = rest.substringAfter('/')
                    published += PublishedEvent(pubsub, topic, body, exchange.requestHeaders.getFirst("Content-Type"))
                    exchange.sendResponseHeaders(publishStatus, -1)
                }
                path.startsWith(INVOKE_PREFIX) -> {
                    val rest = path.removePrefix(INVOKE_PREFIX)
                    val appId = rest.substringBefore('/')
                    val methodPath = rest.substringAfter("/method/", "")
                    val headers = exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.first() }
                    val invocation = Invocation(appId, methodPath, exchange.requestMethod, headers, body)
                    invocations += invocation
                    val stub = stubs["$appId/$methodPath"]
                    val response = stub?.invoke(invocation) ?: StubResponse(404, """{"errorCode":"ERR_NO_STUB"}""")
                    val bytes = response.body.toByteArray()
                    exchange.responseHeaders.add("Content-Type", response.contentType)
                    exchange.sendResponseHeaders(response.status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                    if (bytes.isNotEmpty()) exchange.responseBody.write(bytes)
                }
                else -> exchange.sendResponseHeaders(404, -1)
            }
        }
    }

    companion object {
        private const val PUBLISH_PREFIX = "/v1.0/publish/"
        private const val INVOKE_PREFIX = "/v1.0/invoke/"
        private val mapper = JsonMapper.builder().build()
    }
}
