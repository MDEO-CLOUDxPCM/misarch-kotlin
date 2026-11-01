package org.misarch.simulation.processing

import java.util.UUID

/**
 * In-memory repository of registrations: a list (duplicate ids allowed), lookups return the first match, delete
 * removes all matches. Not thread-safe: used under [SimulationState.lock].
 */
class RegistrationRepository<T : Registration> {

    private val registrations = mutableListOf<T>()

    fun create(registration: T): T = registration.also { registrations.add(it) }

    fun findById(id: UUID?): T? = registrations.firstOrNull { it.id == id }

    fun findAll(): List<T> = registrations.toList()

    fun delete(id: UUID?): Boolean = registrations.removeAll { it.id == id }
}
