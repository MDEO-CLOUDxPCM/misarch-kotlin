package org.misarch.simulation.processing

/**
 * Handles errors after which the process must not continue (broker connection lost, failed reconnect, an exception
 * in the consumer callback). The container is then restarted (`restart: unless-stopped`).
 */
fun interface FatalErrorHandler {
    fun fatal(message: String, cause: Throwable?)
}
