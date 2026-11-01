package org.misarch.simulation.amqp

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import com.rabbitmq.client.DefaultConsumer
import com.rabbitmq.client.Envelope
import com.rabbitmq.client.ShutdownSignalException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.misarch.simulation.config.SimulationConfigurationProperties
import org.misarch.simulation.model.SimulationQueue
import org.misarch.simulation.service.ProcessingService
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds

/**
 * Consumer of both queues: one connection and one channel, queues declared durable, manual ack, no prefetch limit,
 * no automatic recovery. A connection or channel shutdown not caused by the application is fatal.
 *
 * Starts before the web server (lifecycle phase 0; the server's is near `Integer.MAX_VALUE`) and waits until the
 * broker is reachable, so the HTTP port only opens once RabbitMQ is connected.
 */
@Component
class RabbitConsumer(
    properties: SimulationConfigurationProperties,
    private val processingService: ProcessingService,
    private val fatalErrorHandler: FatalErrorHandler,
) : SmartLifecycle {

    private val logger = LoggerFactory.getLogger(RabbitConsumer::class.java)

    private val factory = ConnectionFactory().apply {
        setUri(properties.rabbitmqUrl)
        isAutomaticRecoveryEnabled = false
        isTopologyRecoveryEnabled = false
    }

    /** The current connection and channel; replaced at the minute reset */
    private val session = AtomicReference<Session?>()

    private val running = AtomicBoolean(false)

    override fun getPhase() = 0

    override fun isRunning() = running.get()

    /** Connects (retrying every 5 s until the broker is reachable) and consumes both queues. */
    override fun start() {
        val connected = runBlocking { connectUntilReachable() }
        running.set(true)
        consume(connected)
    }

    override fun stop() {
        running.set(false)
        session.getAndSet(null)?.close()
    }

    /**
     * Rate-limit reset at second 0 of every minute. If a paused queue may process again, the channel is closed (the
     * broker requeues its unacknowledged messages), its remaining deliveries are ignored, and a new connection
     * consumes again; a failed connect is fatal.
     */
    @Scheduled(cron = "\${misarch.simulation.reset-cron}")
    fun resetRateLimit() {
        if (!processingService.resetMessageCounts()) return
        logger.debug("Reconnecting to RabbitMQ...")
        session.get()?.close()
        val reconnected = connect()
        if (reconnected == null) {
            fatalErrorHandler.fatal("Failed to reconnect to RabbitMQ", null)
            return
        }
        consume(reconnected)
    }

    private suspend fun connectUntilReachable(): Session {
        while (true) {
            connect()?.let { return it }
            logger.debug("Retrying in {}.", CONNECT_RETRY_DELAY)
            delay(CONNECT_RETRY_DELAY)
        }
    }

    /** Opens connection and channel and declares both queues durable; null if the broker is not reachable. */
    private fun connect(): Session? = try {
        val connection = factory.newConnection("simulation")
        val channel = connection.createChannel()
        SimulationQueue.entries.forEach { channel.queueDeclare(it.queueName, true, false, false, null) }
        connection.addShutdownListener { onUnexpectedShutdown("connection", it) }
        channel.addShutdownListener { onUnexpectedShutdown("channel", it) }
        logger.info("Connected to RabbitMQ.")
        Session(connection, channel)
    } catch (e: Exception) {
        logger.error("Failed to connect to RabbitMQ", e)
        null
    }

    private fun onUnexpectedShutdown(what: String, cause: ShutdownSignalException) {
        if (!cause.isInitiatedByApplication && running.get()) {
            fatalErrorHandler.fatal("RabbitMQ $what closed unexpectedly: ${cause.message}", cause)
        }
    }

    private fun consume(session: Session) {
        this.session.set(session)
        SimulationQueue.entries.forEach { queue ->
            session.channel.basicConsume(queue.queueName, false, QueueConsumer(session, queue))
        }
    }

    /**
     * An open connection and channel. Once closing, its deliveries are neither processed nor acknowledged: the
     * broker requeues them when the channel closes.
     */
    private inner class Session(val connection: Connection, val channel: Channel) {
        val closing = AtomicBoolean(false)

        fun close() {
            closing.set(true)
            try {
                channel.takeIf { it.isOpen }?.close()
                connection.takeIf { it.isOpen }?.close()
            } catch (e: Exception) {
                logger.error("Error while closing the RabbitMQ connection", e)
            }
        }
    }

    private inner class QueueConsumer(
        private val session: Session,
        private val queue: SimulationQueue,
    ) : DefaultConsumer(session.channel) {

        override fun handleDelivery(
            consumerTag: String,
            envelope: Envelope,
            properties: AMQP.BasicProperties,
            body: ByteArray,
        ) {
            if (session.closing.get()) return
            try {
                if (processingService.consume(queue, body)) session.channel.basicAck(envelope.deliveryTag, false)
            } catch (e: Exception) {
                fatalErrorHandler.fatal("[${queue.queueName}] Error while consuming a message", e)
            }
        }

        override fun handleCancel(consumerTag: String) = processingService.onConsumerCancelled(queue)
    }

    companion object {
        /** Pause between connection attempts at start-up */
        val CONNECT_RETRY_DELAY = 5.seconds
    }
}
