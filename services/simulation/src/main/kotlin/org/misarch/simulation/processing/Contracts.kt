package org.misarch.simulation.processing

import org.slf4j.LoggerFactory

/**
 * Situations in which the original Node process crashes (uncaught exception / unhandled `error` event / unhandled
 * promise rejection) and the container is restarted (`restart: unless-stopped`). The default implementation
 * reproduces that: log and terminate the JVM immediately (no graceful shutdown, like a Node crash).
 */
fun interface FatalErrorHandler {
    fun fatal(message: String, cause: Throwable?)
}

/** Production [FatalErrorHandler]: logs, then `Runtime.halt(1)`. */
class HaltingFatalErrorHandler : FatalErrorHandler {
    private val log = LoggerFactory.getLogger(HaltingFatalErrorHandler::class.java)

    override fun fatal(message: String, cause: Throwable?) {
        log.error("FATAL (the original process crashes here): {}", message, cause)
        System.out.flush()
        System.err.flush()
        Runtime.getRuntime().halt(1)
    }
}

/** A message delivered by the broker (not acknowledged until [ack] is called). */
class IncomingMessage(
    val body: ByteArray,
    private val ackFn: () -> Unit,
    /** True if the delivery came from a channel that is being closed or was replaced (it will be requeued). */
    private val staleFn: () -> Boolean = { false },
) {
    fun ack() = ackFn()
    val isStale: Boolean get() = staleFn()
}

/** Receives deliveries; `message == null` = the broker cancelled the consumer (amqplib calls back with `null`). */
fun interface DeliverySink {
    fun onDelivery(queue: SimulationQueue, message: IncomingMessage?)
}

/** The consuming side of the broker connection (amqplib connection + channel of the original's event processor). */
interface QueueConsumer {
    /** Connects (retrying every 5 s forever) and starts consuming both queues. Blocks until connected. */
    fun start(sink: DeliverySink)

    /**
     * Marks the current channel as closing: deliveries from it must not be processed any more (they are requeued by
     * the broker when the channel closes). Called while holding [SimulationState.lock].
     */
    fun markClosing()

    /** Closes channel and connection (requeues unacked messages), connects once, consumes again. Throws on failure. */
    fun reconnect()

    /** Closes channel and connection. */
    fun shutdown()
}

/** The publishing side (NestJS `ClientRMQ` per queue): fire-and-forget `emit(pattern, data)`. */
fun interface RegistrationPublisher {
    fun emit(queue: SimulationQueue, data: tools.jackson.databind.JsonNode)
}
