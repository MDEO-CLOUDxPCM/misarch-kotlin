package org.misarch.simulation.amqp

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import com.rabbitmq.client.DefaultConsumer
import com.rabbitmq.client.Envelope
import com.rabbitmq.client.ShutdownSignalException
import org.misarch.simulation.processing.DeliverySink
import org.misarch.simulation.processing.FatalErrorHandler
import org.misarch.simulation.processing.IncomingMessage
import org.misarch.simulation.processing.QueueConsumer
import org.misarch.simulation.processing.SimulationQueue
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Consumer of both queues: one connection and one channel, queues declared durable, manual ack, no prefetch limit,
 * no automatic recovery. A connection or channel shutdown not caused by the application is fatal.
 */
@Component
class RabbitConsumer(
    properties: RabbitConfigurationProperties,
    private val fatal: FatalErrorHandler,
) : QueueConsumer {

    private val logger = LoggerFactory.getLogger(RabbitConsumer::class.java)

    private val factory = ConnectionFactory().apply {
        setUri(properties.url)
        isAutomaticRecoveryEnabled = false
        isTopologyRecoveryEnabled = false
    }

    @Volatile private var connection: Connection? = null
    @Volatile private var channel: Channel? = null

    /** Incremented per (re)connection; deliveries of older generations are stale. */
    @Volatile private var generation = 0
    @Volatile private var closingGeneration = -1
    @Volatile private var shuttingDown = false
    private lateinit var sink: DeliverySink

    override fun start(sink: DeliverySink) {
        this.sink = sink
        var connected = connect()
        while (!connected) {
            logger.debug("Retrying in {} seconds.", RETRY_DELAY_MS / 1000)
            Thread.sleep(RETRY_DELAY_MS)
            connected = connect()
        }
        consumeAll()
    }

    /** Opens connection and channel and declares both queues durable. */
    private fun connect(): Boolean = try {
        val newConnection = factory.newConnection("simulation")
        val newChannel = newConnection.createChannel()
        SimulationQueue.entries.forEach { newChannel.queueDeclare(it.queueName, true, false, false, null) }
        newConnection.addShutdownListener { onUnexpectedShutdown("connection", it) }
        newChannel.addShutdownListener { onUnexpectedShutdown("channel", it) }
        connection = newConnection
        channel = newChannel
        generation++
        logger.info("Connected to RabbitMQ.")
        true
    } catch (e: Exception) {
        logger.error("Failed to connect to RabbitMQ", e)
        false
    }

    private fun onUnexpectedShutdown(what: String, cause: ShutdownSignalException) {
        if (!cause.isInitiatedByApplication && !shuttingDown) {
            fatal.fatal("RabbitMQ $what closed unexpectedly: ${cause.message}", cause)
        }
    }

    /** Starts consuming both queues on the current channel. */
    private fun consumeAll() {
        val currentChannel = channel ?: error("not connected")
        val currentGeneration = generation
        SimulationQueue.entries.forEach { queue ->
            currentChannel.basicConsume(queue.queueName, false, Callback(currentChannel, currentGeneration, queue))
        }
    }

    override fun markClosing() {
        closingGeneration = generation
    }

    /**
     * Closes channel (the broker requeues all unacked messages) and connection, connects once and consumes again.
     * Errors while closing are only logged; a failed connect throws.
     */
    override fun reconnect() {
        try {
            channel?.takeIf { it.isOpen }?.close()
            connection?.takeIf { it.isOpen }?.close()
        } catch (e: Exception) {
            logger.error("Failed to reconnect to RabbitMQ", e)
            return
        }
        if (!connect()) throw IllegalStateException("Failed to connect to RabbitMQ")
        consumeAll()
    }

    override fun shutdown() {
        shuttingDown = true
        try {
            channel?.takeIf { it.isOpen }?.close()
            connection?.takeIf { it.isOpen }?.close()
        } catch (e: Exception) {
            logger.debug("Error while closing the RabbitMQ connection", e)
        }
    }

    private inner class Callback(
        private val consumerChannel: Channel,
        private val consumerGeneration: Int,
        private val queue: SimulationQueue,
    ) : DefaultConsumer(consumerChannel) {

        override fun handleDelivery(
            consumerTag: String,
            envelope: Envelope,
            properties: AMQP.BasicProperties,
            body: ByteArray,
        ) {
            try {
                sink.onDelivery(
                    queue,
                    IncomingMessage(
                        body,
                        ackFn = { consumerChannel.basicAck(envelope.deliveryTag, false) },
                        staleFn = { consumerGeneration != generation || consumerGeneration == closingGeneration },
                    )
                )
            } catch (t: Throwable) {
                fatal.fatal("[${queue.queueName}] Error while consuming a message", t)
            }
        }

        override fun handleCancel(consumerTag: String) {
            try {
                sink.onDelivery(queue, null)
            } catch (t: Throwable) {
                fatal.fatal("[${queue.queueName}] Error while handling a consumer cancellation", t)
            }
        }
    }

    companion object {
        /** Pause between connection attempts at start-up */
        const val RETRY_DELAY_MS = 5000L
    }
}
