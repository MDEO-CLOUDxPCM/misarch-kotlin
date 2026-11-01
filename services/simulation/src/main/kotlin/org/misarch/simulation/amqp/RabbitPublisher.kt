package org.misarch.simulation.amqp

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.misarch.simulation.config.SimulationConfigurationProperties
import org.misarch.simulation.model.RegistrationMessage
import org.misarch.simulation.model.SimulationQueue
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

/**
 * Publishes registrations as [RegistrationMessage]s: one connection per queue, opened on the first message, queue
 * declared durable, messages transient (deliveryMode 1) with empty headers. [publish] returns at once: the HTTP
 * response never waits for (or reports) the publish; the order per queue is kept.
 */
@Component
class RabbitPublisher(
    properties: SimulationConfigurationProperties,
    private val jsonMapper: JsonMapper,
    private val scope: CoroutineScope,
) : DisposableBean {

    private val logger = LoggerFactory.getLogger(RabbitPublisher::class.java)

    private val factory = ConnectionFactory().apply {
        setUri(properties.rabbitmqUrl)
        isAutomaticRecoveryEnabled = true
    }

    private val publishers = SimulationQueue.entries.associateWith { QueuePublisher(it) }

    /** Publishes `{"pattern": ..., "data": data}` to the queue in the background. */
    fun publish(queue: SimulationQueue, data: Any) {
        publishers.getValue(queue).publish(jsonMapper.writeValueAsBytes(RegistrationMessage(queue.pattern, data)))
    }

    override fun destroy() = publishers.values.forEach { it.close() }

    /** The publisher of one queue: sends one message at a time, in order. */
    private inner class QueuePublisher(private val queue: SimulationQueue) {

        private val dispatcher = Dispatchers.IO.limitedParallelism(1)

        private val connectionDelegate = lazy { factory.newConnection("simulation-${queue.queueName}") }
        private val connection: Connection by connectionDelegate

        /** Only used on [dispatcher] */
        private var channel: Channel? = null

        fun publish(body: ByteArray) {
            scope.launch(dispatcher) {
                try {
                    channel().basicPublish("", queue.queueName, PROPERTIES, body)
                } catch (e: Exception) {
                    logger.error("[{}] Failed to publish a registration (message lost)", queue.queueName, e)
                }
            }
        }

        private fun channel(): Channel = channel?.takeIf { it.isOpen }
            ?: connection.createChannel().also {
                it.queueDeclare(queue.queueName, true, false, false, null)
                channel = it
            }

        fun close() {
            if (!connectionDelegate.isInitialized()) return
            try {
                connection.takeIf { it.isOpen }?.close()
            } catch (e: Exception) {
                logger.debug("Error while closing the publisher connection", e)
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
