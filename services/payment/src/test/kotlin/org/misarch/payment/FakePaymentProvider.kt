package org.misarch.payment

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The payment provider stub of the tests: records requests, answers `/payment/register` with [status]
 */
object FakePaymentProvider {

    data class Request(val path: String, val headers: Map<String, String>, val body: String)

    val requests: MutableList<Request> = CopyOnWriteArrayList()

    @Volatile
    var status: Int = 201

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            exchange.use {
                val body = it.requestBody.readAllBytes().toString(Charsets.UTF_8)
                val headers = it.requestHeaders.entries.associate { e -> e.key.lowercase() to e.value.first() }
                requests += Request(it.requestURI.path, headers, body)
                it.sendResponseHeaders(if (it.requestURI.path == "/payment/register") status else 404, -1)
            }
        }
        start()
    }

    val url: String get() = "http://127.0.0.1:${server.address.port}"

    fun reset() {
        requests.clear()
        status = 201
    }
}
