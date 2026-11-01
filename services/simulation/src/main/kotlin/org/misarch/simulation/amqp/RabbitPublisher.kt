package org.misarch.simulation.amqp

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import org.misarch.simulation.processing.RegistrationPublisher
import org.misarch.simulation.processing.SimulationQueue
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.util.EnumMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Publishes registrations as [RegistrationMessage]s: one connection per queue, connected lazily on the first emit,
 * queue declared durable, messages transient (deliveryMode 1) with empty headers. [emit] is fire-and-forget: the
 * HTTP response never waits for (or reports) the publish; the order per queue is kept.
 */
@Component
class RabbitPublisher(
    properties: RabbitConfigurationProperties,
    private val jsonMapper: JsonMapper,
) : RegistrationPublisher, DisposableBean {

    private val log = LoggerFactory.getLogger(RabbitPublisher::class.java)

    private val factory = ConnectionFactory().apply {
        setUri(properties.url)
        isAutomaticRecoveryEnabled = true
    }

    private val clients = EnumMap<SimulationQueue, Client>(SimulationQueue::class.java).apply {
        SimulationQueue.entries.forEach { put(it, Client(it)) }
    }

    override fun emit(queue: SimulationQueue, data: Any) {
        clients.getValue(queue).publish(jsonMapper.writeValueAsBytes(RegistrationMessage(queue.pattern, data)))
    }

    override fun destroy() = clients.values.forEach { it.close() }

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
        /** Transient messages (deliveryMode 1), empty headers, no content type. */
        val PROPERTIES: AMQP.BasicProperties = AMQP.BasicProperties.Builder()
            .deliveryMode(1)
            .headers(emptyMap())
            .build()
    }
}
