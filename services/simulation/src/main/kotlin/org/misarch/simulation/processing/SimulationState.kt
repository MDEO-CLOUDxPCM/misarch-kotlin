package org.misarch.simulation.processing

import org.misarch.simulation.payment.Payment
import org.misarch.simulation.shipment.Shipment
import org.springframework.stereotype.Component
import java.util.EnumMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * All mutable state of the simulation (registrations, per-queue message counts and processing flags), guarded by
 * one lock: registration, manual updates, message consumption and the minute reset never interleave.
 */
@Component
class SimulationState {

    val lock = ReentrantLock()

    val payments = RegistrationRepository<Payment>()

    val shipments = RegistrationRepository<Shipment>()

    val messageCounts = EnumMap<SimulationQueue, Int>(SimulationQueue::class.java).apply {
        SimulationQueue.entries.forEach { put(it, 0) }
    }

    val processingAllowed = EnumMap<SimulationQueue, Boolean>(SimulationQueue::class.java).apply {
        SimulationQueue.entries.forEach { put(it, true) }
    }

    fun repository(queue: SimulationQueue): RegistrationRepository<out Registration> = when (queue) {
        SimulationQueue.PAYMENTS -> payments
        SimulationQueue.SHIPMENTS -> shipments
    }

    final inline fun <T> locked(block: SimulationState.() -> T): T = lock.withLock { block() }
}
