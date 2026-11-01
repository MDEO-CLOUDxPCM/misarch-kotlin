package org.misarch.common.persistence

import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * The entity with [id], or `NoSuchElementException()` (no message, i.e.
 * `Exception while fetching data (/x) : null`).
 */
suspend fun <T : Any, ID : Any> CoroutineCrudRepository<T, ID>.findByIdOrThrow(id: ID): T =
    findById(id) ?: throw NoSuchElementException()
