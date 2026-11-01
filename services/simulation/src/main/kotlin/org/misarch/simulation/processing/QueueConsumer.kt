package org.misarch.simulation.processing

/**
 * The consuming side of the broker connection: one connection and one channel for both queues.
 */
interface QueueConsumer {

    /** Connects (retrying every 5 s until the broker is reachable) and starts consuming both queues. */
    fun start(sink: DeliverySink)

    /**
     * Marks the current channel as closing: its deliveries are not processed any more (the broker requeues them when
     * the channel closes). Called while holding [SimulationState.lock].
     */
    fun markClosing()

    /** Closes channel and connection (requeues unacked messages), connects once, consumes again. Throws on failure. */
    fun reconnect()

    /** Closes channel and connection. */
    fun shutdown()
}
