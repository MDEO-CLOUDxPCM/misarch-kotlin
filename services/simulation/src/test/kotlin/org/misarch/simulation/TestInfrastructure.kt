package org.misarch.simulation

import com.sun.net.httpserver.HttpServer
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * One RabbitMQ per test JVM (`rabbitmq:3-management`, the image of the compose setup), removed by Ryuk
 * when the JVM exits.
 */
object RabbitTestBroker {
    const val IMAGE = "rabbitmq:3-management"

    val container: GenericContainer<*> by lazy {
        GenericContainer(IMAGE)
            .withExposedPorts(5672)
            .withEnv("RABBITMQ_DEFAULT_USER", "guest")
            .withEnv("RABBITMQ_DEFAULT_PASS", "guest")
            .waitingFor(Wait.forLogMessage(".*Server startup complete.*\\n", 1).withStartupTimeout(Duration.ofMinutes(2)))
            .also { it.start() }
    }

    fun url(vhost: String? = null): String =
        "amqp://guest:guest@${container.host}:${container.getMappedPort(5672)}" + (vhost?.let { "/$it" } ?: "")

    /** Creates an isolated vhost (own queues) for adapter tests. */
    fun vhost(name: String): String {
        container.execInContainer("rabbitmqctl", "add_vhost", name)
        container.execInContainer("rabbitmqctl", "set_permissions", "-p", name, "guest", ".*", ".*", ".*")
        return url(name)
    }

    fun closeAllConnections(vhost: String) {
        container.execInContainer("rabbitmqctl", "close_all_connections", "-p", vhost, "test")
    }
}

/** Stand-in for payment and shipment: records every callback request, answers 200. */
class CallbackServer : AutoCloseable {
    data class Request(val method: String, val path: String, val contentType: String?, val body: String)

    val requests = LinkedBlockingQueue<Request>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val body = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
            requests += Request(
                exchange.requestMethod, exchange.requestURI.rawPath, exchange.requestHeaders.getFirst("Content-Type"), body
            )
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        start()
    }

    val url: String get() = "http://127.0.0.1:${server.address.port}"

    /** Next callback matching [filter] (others are skipped), within [timeoutSeconds]. */
    fun await(timeoutSeconds: Long = 10, filter: (Request) -> Boolean = { true }): Request {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (true) {
            val remaining = deadline - System.nanoTime()
            val request = requests.poll(maxOf(remaining, 0L), TimeUnit.NANOSECONDS)
                ?: error("no matching callback within $timeoutSeconds s")
            if (filter(request)) return request
        }
    }

    override fun close() = server.stop(0)
}
