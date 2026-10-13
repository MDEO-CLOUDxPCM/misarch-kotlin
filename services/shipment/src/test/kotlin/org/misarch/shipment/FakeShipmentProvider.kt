package org.misarch.shipment

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fake external shipment provider (the `simulation` service's `POST /shipment/register`) for tests: records every
 * request and answers 200, or 500 for the next [failNext] requests / while [alwaysFail] is set.
 */
object FakeShipmentProvider {

    /** A request received by the provider. */
    data class Request(val method: String, val path: String, val contentType: String?, val body: String)

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            exchange.use {
                val body = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
                requests += Request(
                    exchange.requestMethod, exchange.requestURI.rawPath, exchange.requestHeaders.getFirst("Content-Type"), body
                )
                val fail = alwaysFail || failNext.getAndUpdate { if (it > 0) it - 1 else 0 } > 0
                exchange.sendResponseHeaders(if (fail) 500 else 200, -1)
            }
        }
        start()
    }

    val requests: MutableList<Request> = CopyOnWriteArrayList()

    val failNext = AtomicInteger(0)

    @Volatile
    var alwaysFail = false

    val endpoint: String get() = "http://127.0.0.1:${server.address.port}/shipment/register"

    fun reset() {
        requests.clear()
        failNext.set(0)
        alwaysFail = false
    }
}
