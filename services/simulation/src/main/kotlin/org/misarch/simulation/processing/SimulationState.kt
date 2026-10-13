package org.misarch.simulation.processing

import org.springframework.stereotype.Component
import java.util.EnumMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A registered payment or shipment, kept in memory until its queue message is consumed (original `Payment` /
 * `Shipment` entities; `paymentType` only for payments).
 */
class ProviderRecord(val id: String, val paymentType: String?, var blocked: Boolean)

/**
 * The original's in-memory `PaymentRepository` / `ShipmentRepository`: a list (duplicate ids allowed), lookups
 * return the first match, delete removes all matches.
 */
class ProviderRepository {
    private val records = mutableListOf<ProviderRecord>()

    fun create(record: ProviderRecord): ProviderRecord = record.also { records.add(it) }

    fun findById(id: String?): ProviderRecord? = records.firstOrNull { it.id == id }

    fun findAll(): List<ProviderRecord> = records.toList()

    fun delete(id: String?): Boolean = records.removeAll { it.id == id }
}

/**
 * All mutable state of the simulation (repositories, per-queue message counts and processing flags), guarded by ONE
 * lock. The original runs on Node's single thread, so registration, manual updates, message consumption and the
 * minute reset never interleave; the lock reproduces that atomicity.
 */
@Component
class SimulationState {
    val lock = ReentrantLock()

    val payments = ProviderRepository()
    val shipments = ProviderRepository()

    val messageCounts = EnumMap<SimulationQueue, Int>(SimulationQueue::class.java).apply {
        SimulationQueue.entries.forEach { put(it, 0) }
    }
    val processingAllowed = EnumMap<SimulationQueue, Boolean>(SimulationQueue::class.java).apply {
        SimulationQueue.entries.forEach { put(it, true) }
    }

    fun repository(queue: SimulationQueue): ProviderRepository = when (queue) {
        SimulationQueue.PAYMENTS -> payments
        SimulationQueue.SHIPMENTS -> shipments
    }

    final inline fun <T> locked(block: SimulationState.() -> T): T = lock.withLock { block() }
}
