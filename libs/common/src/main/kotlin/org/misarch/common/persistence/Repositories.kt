package org.misarch.common.persistence

import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * The entity with [id], or `NoSuchElementException()` (no message, i.e.
 * `Exception while fetching data (/x) : null`).
 */
suspend fun <T : Any, ID : Any> CoroutineCrudRepository<T, ID>.findByIdOrThrow(id: ID): T =
    findById(id) ?: throw NoSuchElementException()

/**
 * Fails with `NoSuchElementException()` (no message, like [findByIdOrThrow]) if there is no entity with [id].
 */
suspend fun <T : Any, ID : Any> CoroutineCrudRepository<T, ID>.existsByIdOrThrow(id: ID) {
    if (!existsById(id)) {
        throw NoSuchElementException()
    }
}
