package org.misarch.simulation.processing

/**
 * Receives deliveries of the consumer; `message == null` means the broker cancelled the consumer.
 */
fun interface DeliverySink {
    fun onDelivery(queue: SimulationQueue, message: IncomingMessage?)
}
