package org.misarch.simulation.processing

/**
 * A message delivered by the broker, not acknowledged until [ack] is called
 *
 * @property body raw message body
 */
class IncomingMessage(
    val body: ByteArray,
    private val ackFn: () -> Unit,
    private val staleFn: () -> Boolean = { false },
) {
    fun ack() = ackFn()

    /** True if the delivery came from a channel that is being closed or was replaced (the broker requeues it). */
    val isStale: Boolean get() = staleFn()
}
