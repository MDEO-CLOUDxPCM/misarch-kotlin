package org.misarch.simulation.processing

/**
 * Publishes registrations to their queue, fire-and-forget.
 */
fun interface RegistrationPublisher {
    fun emit(queue: SimulationQueue, data: Any)
}
