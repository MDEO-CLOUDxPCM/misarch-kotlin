package org.misarch.simulation.amqp

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import org.misarch.simulation.js.JsJson
import org.misarch.simulation.processing.RegistrationPublisher
import org.misarch.simulation.processing.SimulationQueue
import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode
import java.util.EnumMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The two NestJS `ClientRMQ` clients of the original (`PAYMENT_SERVICE` → `payments-queue`, `SHIPMENT_SERVICE` →
 * `shipments-queue`): one connection per queue, connected lazily on the first emit, queue asserted `durable`,
 * messages `{"pattern": "...", "data": {...}}` sent transient (deliveryMode 1) with empty headers. `emit` is
 * fire-and-forget: the HTTP response never waits for (or reports) the publish; order per queue is kept.
 */
class RabbitPublisher(url: String) : RegistrationPublisher {

    private val log = LoggerFactory.getLogger(RabbitPublisher::class.java)

    private val factory = ConnectionFactory().apply {
        setUri(url)
        // amqp-connection-manager reconnects the original's publishers
        isAutomaticRecoveryEnabled = true
    }

    private val clients = EnumMap<SimulationQueue, Client>(SimulationQueue::class.java).apply {
        SimulationQueue.entries.forEach { put(it, Client(it)) }
    }

    override fun emit(queue: SimulationQueue, data: JsonNode) {
        val packet = JsJson.objectNode().put("pattern", queue.pattern).set("data", data)
        clients.getValue(queue).publish(JsJson.stringify(packet).toByteArray(Charsets.UTF_8))
    }

    fun shutdown() = clients.values.forEach { it.close() }

    private inner class Client(private val queue: SimulationQueue) {
        private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "rmq-publisher-${queue.queueName}").apply { isDaemon = true }
        }
        private var connection: Connection? = null
        private var channel: Channel? = null

        fun publish(body: ByteArray) {
            executor.execute {
                try {
                    channel().basicPublish("", queue.queueName, PROPERTIES, body)
                } catch (e: Exception) {
                    log.error("[{}] Failed to publish a registration (message lost)", queue.queueName, e)
                }
            }
        }

        /** Lazy connect (NestJS `ClientProxy.connect()` on the first emit). */
        private fun channel(): Channel {
            channel?.takeIf { it.isOpen }?.let { return it }
            val currentConnection = connection?.takeIf { it.isOpen }
                ?: factory.newConnection("simulation-${queue.queueName}").also { connection = it }
            return currentConnection.createChannel().also {
                it.queueDeclare(queue.queueName, true, false, false, null)
                channel = it
            }
        }

        fun close() {
            executor.shutdown()
            executor.awaitTermination(2, TimeUnit.SECONDS)
            try {
                connection?.takeIf { it.isOpen }?.close()
            } catch (e: Exception) {
                log.debug("Error while closing the publisher connection", e)
            }
        }
    }

    companion object {
        /** amqplib `sendToQueue(..., {persistent: false, headers: {}})`: deliveryMode 1, no content type. */
        val PROPERTIES: AMQP.BasicProperties = AMQP.BasicProperties.Builder()
            .deliveryMode(1)
            .headers(emptyMap())
            .build()
    }
}
