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

/**
 * The consumer connection of the original's `EventProcessorService` (amqplib): ONE connection and ONE channel for
 * both queues, queues asserted `durable`, `consume(queue, cb, {noAck: false})`, no `prefetch` (unlimited), no
 * automatic recovery. A connection/channel failure not caused by the application is fatal, like the unhandled
 * `error` event that crashes the original.
 */
class RabbitConsumer(
    url: String,
    private val fatal: FatalErrorHandler,
    private val retryDelayMillis: Long = 5000,
) : QueueConsumer {

    private val log = LoggerFactory.getLogger(RabbitConsumer::class.java)

    private val factory = ConnectionFactory().apply {
        setUri(url)
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
            log.debug("Retrying in {} seconds.", retryDelayMillis / 1000)
            Thread.sleep(retryDelayMillis)
            connected = connect()
        }
        consumeAll()
    }

    /** `connectToRabbitMQ()`: connection + channel + `assertQueue(durable)` for both queues. */
    private fun connect(): Boolean = try {
        val newConnection = factory.newConnection("simulation")
        val newChannel = newConnection.createChannel()
        SimulationQueue.entries.forEach { newChannel.queueDeclare(it.queueName, true, false, false, null) }
        newConnection.addShutdownListener { onUnexpectedShutdown("connection", it) }
        newChannel.addShutdownListener { onUnexpectedShutdown("channel", it) }
        connection = newConnection
        channel = newChannel
        generation++
        log.info("Connected to RabbitMQ.")
        true
    } catch (e: Exception) {
        log.error("Failed to connect to RabbitMQ", e)
        false
    }

    private fun onUnexpectedShutdown(what: String, cause: ShutdownSignalException) {
        if (!cause.isInitiatedByApplication && !shuttingDown) {
            fatal.fatal("RabbitMQ $what closed unexpectedly: ${cause.message}", cause)
        }
    }

    /** `initializeConsumer(queue)` for both queues on the current channel. */
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
     * `reconnectToRabbitMQ()`: close channel (the broker requeues all unacked messages) and connection, connect once,
     * consume again. Errors while closing are only logged (as in the original); a failed connect throws.
     */
    override fun reconnect() {
        try {
            channel?.takeIf { it.isOpen }?.close()
            connection?.takeIf { it.isOpen }?.close()
        } catch (e: Exception) {
            log.error("Failed to reconnect to RabbitMQ", e)
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
            log.debug("Error while closing the RabbitMQ connection", e)
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
                // an exception in the amqplib consumer callback crashes the original process
                fatal.fatal("[${queue.queueName}] Error while consuming a message", t)
            }
        }

        override fun handleCancel(consumerTag: String) {
            // amqplib calls the consumer callback with null when the broker cancels the consumer
            try {
                sink.onDelivery(queue, null)
            } catch (t: Throwable) {
                fatal.fatal("[${queue.queueName}] Error while handling a consumer cancellation", t)
            }
        }
    }
}
